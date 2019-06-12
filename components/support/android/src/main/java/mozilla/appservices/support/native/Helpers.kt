/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.appservices.support.native

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import com.sun.jna.Library
import com.sun.jna.Native
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A helper for converting a protobuf Message into a direct `java.nio.ByteBuffer`
 * and it's length. This avoids a copy when passing data to Rust, when compared
 * to using an `Array<Byte>`
 */

fun <T : MessageLite> T.toNioDirectBuffer(): Pair<ByteBuffer, Int> {
    val len = this.serializedSize
    val nioBuf = ByteBuffer.allocateDirect(len)
    nioBuf.order(ByteOrder.nativeOrder())
    val output = CodedOutputStream.newInstance(nioBuf)
    this.writeTo(output)
    output.checkNoSpaceLeft()
    return Pair(first = nioBuf, second = len)
}

sealed class MegazordError : Exception {
    /**
     * The name of the component we were trying to initialize when we had the error.
     */
    val componentName: String
    constructor(componentName: String, msg: String) : super(msg) {
        this.componentName = componentName
    }
    constructor(componentName: String, msg: String, cause: Throwable) : super(msg, cause) {
        this.componentName = componentName
    }
}

class MegazordNotAvailable(
    componentName: String,
    cause: UnsatisfiedLinkError
) : MegazordError(componentName, "Failed to locate megazord library: '$componentName'", cause)

class IncompatibleMegazordVersion(
    componentName: String,
    val componentVersion: String,
    val megazordLibrary: String,
    val megazordVersion: String?
) : MegazordError(
    componentName,
    "Incompatible megazord version: library \"$componentName\" was compiled expecting " +
    "app-services version \"$componentVersion\", but the megazord \"$megazordLibrary\" provides " +
        "version \"${megazordVersion ?: "unknown"}\""
)

class MegazordNotInitialized(componentName: String) : MegazordError(
    componentName,
    "The application-services megazord has not yet been initialized, but is needed by \"$componentName\""
)

/**
 * I think we'd expect this to be caused by the following two things both happening
 *
 * 1. Substitution not actually replacing the full megazord
 * 2. Megazord initialization getting called after the first attempt to load something from the
 *    megazord, causing us to fall back to checking the full-megazord (and finding it, because
 *    of #1).
 *
 * It's very unlikely, but if it did happen it could be a memory safety error, so we check.
 */
class MultipleMegazordsPresent(
    componentName: String,
    val loadedMegazord: String,
    val requestedMegazord: String
) : MegazordError(
    componentName,
    "Multiple megazords are present, and bindings have already been loaded from " +
        "\"$loadedMegazord\" when a request to load $componentName from $requestedMegazord " +
        "is made. (This probably stems from an error in your build configuration)"
)

internal const val FULL_MEGAZORD_LIBRARY: String = "megazord"

internal fun doMegazordCheck(componentName: String, componentVersion: String): String {
    val mzLibrary = System.getProperty("mozilla.appservices.megazord.library")
    if (mzLibrary == null) {
        // If it's null, then the megazord hasn't been initialized.
        if (checkFullMegazord(componentName, componentVersion)) {
            return FULL_MEGAZORD_LIBRARY
        }
        throw MegazordNotInitialized(componentName)
    }

    // Assume it's properly initialized if it's been initialized at all
    val mzVersion = System.getProperty("mozilla.appservices.megazord.version")!!

    // We require exact equality, since we don't perform a major version
    // bump if we change the ABI. In practice, this seems unlikely to
    // cause problems, but we could come up with a scheme if this proves annoying.
    if (componentVersion != mzVersion) {
        throw IncompatibleMegazordVersion(componentName, componentVersion, mzLibrary, mzVersion)
    }
    return mzLibrary
}

/**
 * Determine the megazord library name, and check that it's version is
 * compatible with the version of our bindings. Returns the megazord
 * library name.
 *
 * Note: This is only public because it's called by an inline function.
 * It should not be called by consumers.
 */
@Synchronized
fun megazordCheck(componentName: String, componentVersion: String): String {
    val mzLibraryUsed = System.getProperty("mozilla.appservices.megazord.library.used")
    val mzLibraryDetermined = doMegazordCheck(componentName, componentVersion)

    // If we've already initialized the megazord, that means we've probably already loaded bindings
    // from it somewhere. It would be a big problem for us to use some bindings from one lib and
    // some from another, so we just fail.
    if (mzLibraryUsed != null && mzLibraryDetermined != mzLibraryUsed) {
        throw MultipleMegazordsPresent(componentName, mzLibraryUsed, mzLibraryDetermined)
    }

    // Mark that we're about to load bindings from the specified lib. Note that we don't do this
    // in the case that the megazord check threw.
    if (mzLibraryUsed != null) {
        System.setProperty("mozilla.appservices.megazord.library.used", mzLibraryDetermined)
    }
    return mzLibraryDetermined
}

/**
 * Contains all the boilerplate for loading a
 *
 * Indirect as in, we aren't using JNA direct mapping. Eventually we'd
 * like to (it's faster), but that's a problem for another day.
 */
inline fun <reified Lib : Library> loadIndirect(componentName: String, componentVersion: String): Lib {
    val mzLibrary = megazordCheck(componentName, componentVersion)
    return try {
        Native.load<Lib>(mzLibrary, Lib::class.java)
    } catch (e: UnsatisfiedLinkError) {
        // TODO: This should probably be a hard error now, right?
        Proxy.newProxyInstance(
            Lib::class.java.classLoader,
            arrayOf(Lib::class.java)) { _, _, _ ->
            throw MegazordNotAvailable(componentName, e)
        } as Lib
    }
}

// See the comment on full_megazord_get_version for background
// on why this exists and what we use it for.
@Suppress("FunctionNaming")
internal interface LibMegazordFfi : Library {
    // Note: Rust doesn't want us to free this string (because
    // it's a pain for us to arrange here), so it is actually
    // correct for us to return a String over the FFI for this.
    fun full_megazord_get_version(): String?
}

/**
 * Try and load the full megazord library, call the function for getting its
 * version, and check it against componentVersion.
 *
 * - If the megazord does not exist, returns false
 * - If the megazord exists and the version is valid, returns true.
 * - If the megazord exists and the version is invalid, throws a IncompatibleMegazordVersion error.
 *   (This is done here instead of returning false so that we can provide better info in the error)
 */
internal fun checkFullMegazord(componentName: String, componentVersion: String): Boolean {
    return try {
        // It's not ideal to do this every time, but it should be rare, not too costly,
        // and the workaround for the app is simple (just init the megazord).
        val lib = Native.load<LibMegazordFfi>(FULL_MEGAZORD_LIBRARY, LibMegazordFfi::class.java)

        val version = lib.full_megazord_get_version()
            ?: throw IncompatibleMegazordVersion(componentName, componentVersion, FULL_MEGAZORD_LIBRARY, null)

        if (version != componentVersion) {
            throw IncompatibleMegazordVersion(componentName, componentVersion, FULL_MEGAZORD_LIBRARY, version)
        }

        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }
}