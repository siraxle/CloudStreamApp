import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class FfmpegLibraryPatchPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val androidComponents = target.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                FfmpegLibraryPatchTransform::class.java,
                InstrumentationScope.ALL,
            ) {}
            variant.instrumentation.transformClassesWith(
                FfmpegAudioDecoderPatchTransform::class.java,
                InstrumentationScope.ALL,
            ) {}
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COPY_FRAMES,
            )
        }
    }
}
