package com.ridetrack.app.studio

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * The Red only filter: reds stay in colour (a jacket, a tank), everything else goes to black and
 * white with [contrast]; [amount] blends it with the original.
 */
@UnstableApi
internal class KeepRedEffect(private val amount: Float, private val contrast: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = KeepRedProgram(amount, contrast)
}

@UnstableApi
private class KeepRedProgram(private val amount: Float, private val contrast: Float) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uAmount", amount)
            program.setFloatUniform("uContrast", contrast)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
        // How red a pixel is: red well above green and blue, and not washed out. Soft edges, so
        // the boundary doesn't fizz.
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uAmount;
uniform float uContrast;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
  float g = clamp((l - 0.5) * uContrast + 0.5, 0.0, 1.0);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float sat = mx > 0.0 ? (mx - mn) / mx : 0.0;
  float lead = c.r - max(c.g, c.b);
  float red = smoothstep(0.08, 0.22, lead) * smoothstep(0.3, 0.5, sat);
  vec3 filtered = mix(vec3(g), c.rgb, red);
  gl_FragColor = vec4(mix(c.rgb, filtered, uAmount), c.a);
}
"""
    }
}
