import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

// ── FfmpegLibrary patch ───────────────────────────────────────────────────────
// Adds  case "audio/x-ape": return "ape";  to FfmpegLibrary.getCodecName().

abstract class FfmpegLibraryPatchTransform :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = FfmpegLibraryClassVisitor(Opcodes.ASM9, nextClassVisitor)

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "androidx.media3.decoder.ffmpeg.FfmpegLibrary"
}

private class FfmpegLibraryClassVisitor(api: Int, next: ClassVisitor) : ClassVisitor(api, next) {
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return if (name == "getCodecName" && descriptor == "(Ljava/lang/String;)Ljava/lang/String;") {
            ApeGetCodecNamePatcher(Opcodes.ASM9, mv)
        } else {
            mv
        }
    }
}

// Prepends to getCodecName:  if ("audio/x-ape".equals(mimeType)) return "ape";
private class ApeGetCodecNamePatcher(api: Int, mv: MethodVisitor) : MethodVisitor(api, mv) {
    override fun visitCode() {
        super.visitCode()
        val skip = Label()
        mv.visitLdcInsn("audio/x-ape")
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "equals",
            "(Ljava/lang/Object;)Z",
            false,
        )
        mv.visitJumpInsn(Opcodes.IFEQ, skip)
        mv.visitLdcInsn("ape")
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(skip)
    }
}

// ── FfmpegAudioDecoder patch ──────────────────────────────────────────────────
// FfmpegAudioDecoder.getExtraData() has no case for "audio/x-ape" and falls to
// default: return null.  Without extradata FFmpeg's APE decoder fails with
// avcodec_open2: Invalid argument (extradata_size < 6).
//
// This patch prepends to getExtraData:
//   if ("audio/x-ape".equals(mimeType))
//       return initializationData.isEmpty() ? null : (byte[]) initializationData.get(0);

abstract class FfmpegAudioDecoderPatchTransform :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = FfmpegAudioDecoderClassVisitor(Opcodes.ASM9, nextClassVisitor)

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder"
}

private class FfmpegAudioDecoderClassVisitor(api: Int, next: ClassVisitor) : ClassVisitor(api, next) {
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return when {
            name == "getExtraData" && descriptor == "(Ljava/lang/String;Ljava/util/List;)[B" ->
                ApeGetExtraDataPatcher(Opcodes.ASM9, mv)
            // Patch the constructor to enlarge outputBufferSize to 4 MiB when the codec
            // is "ape".  APE blocks decompress to up to 4 MB of PCM per frame (Insane
            // level = 1_048_576 samples × 2ch × 2B), but the Jellyfin decoder starts
            // with a 65 535-byte initial buffer which is far too small.
            name == "<init>" ->
                ApeOutputBufferSizePatcher(Opcodes.ASM9, mv)
            else -> mv
        }
    }
}

private class ApeGetExtraDataPatcher(api: Int, mv: MethodVisitor) : MethodVisitor(api, mv) {
    override fun visitCode() {
        super.visitCode()
        val skip = Label()
        val notEmpty = Label()

        // if (!"audio/x-ape".equals(mimeType)) goto skip
        mv.visitLdcInsn("audio/x-ape")
        mv.visitVarInsn(Opcodes.ALOAD, 0)           // mimeType (param 0 of static method)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "equals",
            "(Ljava/lang/Object;)Z",
            false,
        )
        mv.visitJumpInsn(Opcodes.IFEQ, skip)

        // if (!initializationData.isEmpty()) goto notEmpty
        mv.visitVarInsn(Opcodes.ALOAD, 1)           // initializationData (param 1)
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "java/util/List",
            "isEmpty",
            "()Z",
            true,
        )
        mv.visitJumpInsn(Opcodes.IFEQ, notEmpty)

        // return null
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)

        // return (byte[]) initializationData.get(0)
        mv.visitLabel(notEmpty)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "java/util/List",
            "get",
            "(I)Ljava/lang/Object;",
            true,
        )
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[B")
        mv.visitInsn(Opcodes.ARETURN)

        mv.visitLabel(skip)
    }
}

// Intercepts the constructor: after `outputBufferSize` is written, if codecName == "ape"
// override it to APE_OUTPUT_BUFFER_SIZE (4 MiB) so APE blocks always fit without JNI
// buffer resizing.  codecName is stored before outputBufferSize in the constructor, so
// reading it here is safe.
private const val APE_DECODER_CLASS = "androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder"
private const val APE_OUTPUT_BUFFER_SIZE = 4 * 1024 * 1024   // 4 MiB

private class ApeOutputBufferSizePatcher(api: Int, mv: MethodVisitor) : MethodVisitor(api, mv) {
    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        if (opcode == Opcodes.PUTFIELD && name == "outputBufferSize" && owner == APE_DECODER_CLASS) {
            val skip = Label()
            // if (!"ape".equals(this.codecName)) goto skip
            mv.visitLdcInsn("ape")
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, APE_DECODER_CLASS, "codecName", "Ljava/lang/String;")
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
            mv.visitJumpInsn(Opcodes.IFEQ, skip)
            // this.outputBufferSize = APE_OUTPUT_BUFFER_SIZE
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitLdcInsn(APE_OUTPUT_BUFFER_SIZE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, APE_DECODER_CLASS, "outputBufferSize", "I")
            mv.visitLabel(skip)
        }
    }
}
