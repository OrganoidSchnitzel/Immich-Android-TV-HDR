package nl.giejay.mediaslider.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.max

/**
 * Draws an Ultra HDR bitmap (SDR base + gain map) into the currently bound BT.2020 PQ surface.
 *
 * The gain map is applied exactly as the platform documents it, on linearised SDR values:
 *
 *     logRatio = lerp(log2(ratioMin), log2(ratioMax), gain ^ (1 / gamma))
 *     hdr      = (sdr + epsilonSdr) * 2^(logRatio * W) - epsilonHdr
 *
 * where `W` is how much of the available gain to apply (1.0 = the full HDR rendition the photo was
 * authored for). The result is relative to SDR white, so it is scaled to absolute luminance with
 * SDR white at [sdrWhiteNits] (203 nits, the BT.2408 reference white) and PQ-encoded for the
 * 10-bit surface.
 *
 * All GL calls must happen on the thread that owns the EGL context.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UltraHdrGlRenderer(private val sdrWhiteNits: Float = DEFAULT_SDR_WHITE_NITS) {

    private var program = 0
    private var baseTexture = 0
    private var gainTexture = 0
    private val vertexBuffer: FloatBuffer = allocateFloats(VERTICES_PER_QUAD * FLOATS_PER_VERTEX)

    fun setUp() {
        program = buildProgram()
        baseTexture = createTexture()
        gainTexture = createTexture()
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        val textures = intArrayOf(baseTexture, gainTexture).filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures, 0)
        }
        baseTexture = 0
        gainTexture = 0
    }

    /**
     * Uploads [bitmap] and its gain map. Returns false when the bitmap is unusable (recycled, or
     * the gain map went away), so the caller can fall back to the normal SDR image view.
     */
    fun uploadImage(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || !bitmap.hasGainmap()) return false
        val gainmapContents = bitmap.gainmap?.gainmapContents ?: return false
        if (gainmapContents.isRecycled) return false
        upload(baseTexture, bitmap)
        upload(gainTexture, gainmapContents)
        return true
    }

    /**
     * Draws the uploaded image letterboxed into [surfaceWidth] x [surfaceHeight], keeping the
     * bitmap's aspect ratio (what an ImageView with centerInside does). Glide has already cropped
     * or scaled the bitmap, so no further cropping happens here.
     */
    fun draw(bitmap: Bitmap, surfaceWidth: Int, surfaceHeight: Int, weight: Float, usePq: Boolean) {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (bitmap.isRecycled || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val gainmap = bitmap.gainmap ?: return

        fillQuad(bitmap.width, bitmap.height, surfaceWidth, surfaceHeight)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        bindTexture(GLES20.GL_TEXTURE0, baseTexture, "uBase", 0)
        bindTexture(GLES20.GL_TEXTURE1, gainTexture, "uGain", 1)

        applyGainmapUniforms(gainmap, weight)
        applyColorUniforms(bitmap, gainmap)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uUsePq"), if (usePq) 1f else 0f)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTICES_PER_QUAD)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun applyGainmapUniforms(gainmap: Gainmap, weight: Float) {
        uniform3(gainmap.ratioMin.map { log2(max(it, MIN_RATIO)) }, "uLogRatioMin")
        uniform3(gainmap.ratioMax.map { log2(max(it, MIN_RATIO)) }, "uLogRatioMax")
        uniform3(gainmap.gamma.map { 1f / max(it, MIN_GAMMA) }, "uInvGamma")
        uniform3(gainmap.epsilonSdr.toList(), "uEpsilonSdr")
        uniform3(gainmap.epsilonHdr.toList(), "uEpsilonHdr")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uWeight"), weight.coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSdrWhiteNits"), sdrWhiteNits)
    }

    private fun applyColorUniforms(bitmap: Bitmap, gainmap: Gainmap) {
        val gainIsSingleChannel = gainmap.gainmapContents.config == Bitmap.Config.ALPHA_8
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uGainIsAlpha"),
            if (gainIsSingleChannel) 1f else 0f
        )
        val sourceSpace = hdrPrimaries(bitmap, gainmap)
        GLES20.glUniformMatrix3fv(
            GLES20.glGetUniformLocation(program, "uToBt2020"), 1, false,
            ColorMath.toBt2020Matrix(sourceSpace), 0
        )
        val transfer = ColorMath.transferParameters(bitmap.colorSpace as? ColorSpace.Rgb)
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(program, "uTransferGab"),
            transfer[0], transfer[1], transfer[2]
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(program, "uTransferCdef"),
            transfer[3], transfer[4], transfer[5], transfer[6]
        )
    }

    /**
     * Primaries the HDR rendition is expressed in. A gain map may name its own
     * (`alternativeImagePrimaries`, added in Android 16); otherwise the HDR result shares the base
     * image's primaries.
     */
    private fun hdrPrimaries(bitmap: Bitmap, gainmap: Gainmap): ColorSpace.Rgb {
        val alternative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            gainmap.alternativeImagePrimaries
        } else {
            null
        }
        return (alternative ?: bitmap.colorSpace) as? ColorSpace.Rgb
            ?: ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
    }

    private fun uniform3(values: List<Float>, name: String) {
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(program, name),
            values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f }, values.getOrElse(2) { 0f }
        )
    }

    private fun bindTexture(unit: Int, texture: Int, uniform: String, index: Int) {
        GLES20.glActiveTexture(unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, uniform), index)
    }

    /** Position + texture coordinates for a centred, aspect-preserving quad. */
    private fun fillQuad(imageWidth: Int, imageHeight: Int, surfaceWidth: Int, surfaceHeight: Int) {
        val imageAspect = imageWidth.toFloat() / imageHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val halfWidth = if (imageAspect > surfaceAspect) 1f else imageAspect / surfaceAspect
        val halfHeight = if (imageAspect > surfaceAspect) surfaceAspect / imageAspect else 1f
        vertexBuffer.clear()
        // Triangle strip: bottom-left, bottom-right, top-left, top-right. Texture v is flipped
        // because bitmaps start at the top row while GL texture space starts at the bottom.
        vertexBuffer.put(floatArrayOf(-halfWidth, -halfHeight, 0f, 1f))
        vertexBuffer.put(floatArrayOf(halfWidth, -halfHeight, 1f, 1f))
        vertexBuffer.put(floatArrayOf(-halfWidth, halfHeight, 0f, 0f))
        vertexBuffer.put(floatArrayOf(halfWidth, halfHeight, 1f, 0f))
        vertexBuffer.position(0)
    }

    private fun upload(texture: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun createTexture(): Int {
        val handles = IntArray(1)
        GLES20.glGenTextures(1, handles, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handles[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return handles[0]
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertex)
        GLES20.glAttachShader(handle, fragment)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Could not link HDR program: ${GLES20.glGetProgramInfoLog(handle)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return handle
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Timber.e("HDR shader failed to compile: %s", log)
            error("Could not compile HDR shader: $log")
        }
        return shader
    }

    private fun allocateFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun log2(value: Float): Float = (ln(value.toDouble()) / LN_2).toFloat()

    companion object {
        /** BT.2408 reference white: where diffuse SDR white sits on an HDR display. */
        const val DEFAULT_SDR_WHITE_NITS = 203f

        private const val LN_2 = 0.6931471805599453
        private const val MIN_RATIO = 1e-6f
        private const val MIN_GAMMA = 1e-6f
        private const val VERTICES_PER_QUAD = 4
        private const val FLOATS_PER_VERTEX = 4
        private const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        private val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uBase;
            uniform sampler2D uGain;
            uniform vec3 uLogRatioMin;
            uniform vec3 uLogRatioMax;
            uniform vec3 uInvGamma;
            uniform vec3 uEpsilonSdr;
            uniform vec3 uEpsilonHdr;
            uniform float uWeight;
            uniform float uGainIsAlpha;
            uniform mat3 uToBt2020;
            uniform float uSdrWhiteNits;
            uniform float uUsePq;
            // Parametric transfer function of the base image, split over two uniforms so no
            // uniform array location has to be queried (drivers disagree on the name to use).
            uniform vec3 uTransferGab;
            uniform vec4 uTransferCdef;

            float toLinear(float x) {
                float g = uTransferGab.x;
                float a = uTransferGab.y;
                float b = uTransferGab.z;
                float c = uTransferCdef.x;
                float d = uTransferCdef.y;
                float e = uTransferCdef.z;
                float f = uTransferCdef.w;
                return x >= d ? pow(max(a * x + b, 0.0), g) + e : c * x + f;
            }

            float pqFromNits(float nits) {
                float y = clamp(nits / 10000.0, 0.0, 1.0);
                float ym = pow(y, 0.1593017578125);
                return pow((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym), 78.84375);
            }

            // BT.2100 HLG. Scene light is normalised so diffuse white lands on the 75% signal
            // level of BT.2408, which leaves about 3.8x of headroom above it before clipping.
            float hlgFromRelative(float relative) {
                float e = clamp(relative * 0.26496, 0.0, 1.0);
                if (e <= 1.0 / 12.0) {
                    return sqrt(3.0 * e);
                }
                return 0.17883277 * log(12.0 * e - 0.28466892) + 0.55991073;
            }

            float encode(float relative) {
                return mix(hlgFromRelative(relative), pqFromNits(relative * uSdrWhiteNits), uUsePq);
            }

            void main() {
                vec3 encoded = texture2D(uBase, vTexCoord).rgb;
                vec3 sdr = vec3(toLinear(encoded.r), toLinear(encoded.g), toLinear(encoded.b));

                vec4 gainSample = texture2D(uGain, vTexCoord);
                vec3 gain = mix(gainSample.rgb, vec3(gainSample.a), uGainIsAlpha);
                vec3 shaped = pow(clamp(gain, 0.0, 1.0), uInvGamma);
                vec3 logRatio = uLogRatioMin + (uLogRatioMax - uLogRatioMin) * shaped;

                vec3 hdr = (sdr + uEpsilonSdr) * exp2(logRatio * uWeight) - uEpsilonHdr;
                // Relative to diffuse white: 1.0 is SDR white, higher values are the highlights
                // the gain map recovers.
                vec3 wide = max(uToBt2020 * max(hdr, 0.0), 0.0);

                gl_FragColor = vec4(encode(wide.r), encode(wide.g), encode(wide.b), 1.0);
            }
        """.trimIndent()
    }
}
