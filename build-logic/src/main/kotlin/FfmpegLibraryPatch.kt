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
// FfmpegAudioDecoder.getExtraData() has no case for "audio/x-ape"; falls to
// default: return null.  Without extradata avcodec_open2 fails (extradata_size<6).
//
// Instead of patching getExtraData() body (which has frame-computation edge cases
// with COPY_FRAMES mode), we intercept the call-site in the constructor: when
// invokestatic getExtraData(String,List) is encountered and mimeType=="audio/x-ape",
// we inline the List.get(0) retrieval and skip the original call entirely.
//
// The constructor also writes outputBufferSize, which we bump to 4 MiB for APE
// (Insane level = 1,048,576 samples × 2ch × 2B).  Both patches live in
// ApeConstructorPatcher so we only need one pass over the constructor.

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
        return if (name == "<init>") ApeConstructorPatcher(Opcodes.ASM9, mv) else mv
    }
}

private const val APE_DECODER_CLASS = "androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder"
private const val APE_OUTPUT_BUFFER_SIZE = 4 * 1024 * 1024   // 4 MiB

// Patches the FfmpegAudioDecoder constructor for two things:
//
//  1. getExtraData call-site (visitMethodInsn):
//     invokestatic getExtraData(String mimeType, List initData) is replaced inline:
//     if mimeType=="audio/x-ape" → push initData.get(0) (or null); else call original.
//     Local slots 6 & 7 are used as scratch (params end at slot 5: this,format,3×int,bool).
//
//  2. outputBufferSize write (visitFieldInsn):
//     After PUTFIELD outputBufferSize, if codecName=="ape" override value to 4 MiB.
private class ApeConstructorPatcher(api: Int, mv: MethodVisitor) : MethodVisitor(api, mv) {

    override fun visitMethodInsn(
        opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean,
    ) {
        if (opcode == Opcodes.INVOKESTATIC
            && name == "getExtraData"
            && owner == APE_DECODER_CLASS
            && descriptor == "(Ljava/lang/String;Ljava/util/List;)[B"
        ) {
            // Stack: [..., this_for_putfield, String mimeType, List initData]
            val callOriginal = Label()
            val done = Label()
            val isEmpty = Label()
            val localInitData = 6
            val localMimeType = 7

            // Save both args to scratch locals
            mv.visitVarInsn(Opcodes.ASTORE, localInitData)
            mv.visitVarInsn(Opcodes.ASTORE, localMimeType)
            // Stack: [..., this_for_putfield]

            // if (!"audio/x-ape".equals(mimeType)) → fall through to original call
            mv.visitLdcInsn("audio/x-ape")
            mv.visitVarInsn(Opcodes.ALOAD, localMimeType)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false,
            )
            mv.visitJumpInsn(Opcodes.IFEQ, callOriginal)

            // APE branch: push initData.isEmpty() ? null : (byte[]) initData.get(0)
            mv.visitVarInsn(Opcodes.ALOAD, localInitData)
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true)
            mv.visitJumpInsn(Opcodes.IFNE, isEmpty)            // jump if empty
            mv.visitVarInsn(Opcodes.ALOAD, localInitData)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true,
            )
            mv.visitTypeInsn(Opcodes.CHECKCAST, "[B")
            mv.visitJumpInsn(Opcodes.GOTO, done)
            mv.visitLabel(isEmpty)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitJumpInsn(Opcodes.GOTO, done)

            // Non-APE: restore args and call original static method
            mv.visitLabel(callOriginal)
            mv.visitVarInsn(Opcodes.ALOAD, localMimeType)
            mv.visitVarInsn(Opcodes.ALOAD, localInitData)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

            mv.visitLabel(done)
            // Stack: [..., this_for_putfield, byte[] or null]
            return
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        // Ensure slots 6 & 7 (scratch locals we introduce) are within maxLocals.
        super.visitMaxs(maxStack.coerceAtLeast(4), maxLocals.coerceAtLeast(8))
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        if (opcode == Opcodes.PUTFIELD && name == "outputBufferSize" && owner == APE_DECODER_CLASS) {
            val skip = Label()
            // if (!"ape".equals(this.codecName)) goto skip
            mv.visitLdcInsn("ape")
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, APE_DECODER_CLASS, "codecName", "Ljava/lang/String;")
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false,
            )
            mv.visitJumpInsn(Opcodes.IFEQ, skip)
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitLdcInsn(APE_OUTPUT_BUFFER_SIZE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, APE_DECODER_CLASS, "outputBufferSize", "I")
            mv.visitLabel(skip)
        }
    }
}
