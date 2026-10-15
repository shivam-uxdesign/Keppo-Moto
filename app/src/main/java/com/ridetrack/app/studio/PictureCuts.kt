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
 * Transitions that change the picture itself, not only the camera and the graphics on top: Split
 * (the frame parts down the middle into the dark), Blur, and Luma fade (the dark parts go first,
 * the bright parts of the next clip come first). As every transition here, each clip does its own
 * half: out to the cut, then the next clip in from it. The timing is pure, so it's unit-tested.
 */
object PictureCuts {
    val KINDS = setOf(TransitionKind.SPLIT, TransitionKind.BLUR, TransitionKind.LUMA)

    /** A picture transition at a frame: which, and how far ([cov] 0 → 1 at the cut). */
    data class At(val kind: TransitionKind, val cov: Float)

    /** The picture transition at frame [f], or null when none is playing. */
    fun at(f: FrameAt): At? {
        val t = f.localMs.toFloat()
        if (f.hasPrev) {
            val k = f.inKind.on(f.vibe)
            val d = f.inMs / 2f
            if (k in KINDS && d > 0f && t < d) return At(k, (1 - t / d).coerceIn(0f, 1f))
        }
        if (f.hasNext) {
            val k = f.outKind.on(f.vibe)
            val d = f.outMs / 2f
            if (k in KINDS && d > 0f && t > f.durMs - d) return At(k, ((t - (f.durMs - d)) / d).coerceIn(0f, 1f))
        }
        return null
    }

    /** Whether a clip with frame [f]'s cuts ever needs the picture effect (so others skip it). */
    fun touches(f: FrameAt): Boolean = (f.hasPrev && f.inKind.on(f.vibe) in KINDS) || (f.hasNext && f.outKind.on(f.vibe) in KINDS)

    /** The shaders' number for [k]. */
    fun code(k: TransitionKind?): Float = when (k) {
        TransitionKind.SPLIT -> 1f
        TransitionKind.BLUR -> 2f
        TransitionKind.LUMA -> 3f
        else -> 0f
    }

    /** How far Blur blurs at the cut, as a share of the frame's width. */
    const val BLUR_REACH = 0.045f

    /** The editor preview's version (AGSL, Android 13+): the same picture as the made video's. */
    const val AGSL = """
uniform shader content;
uniform float kind;
uniform float cov;
uniform float2 size;
uniform float3 ink;
half4 main(float2 xy) {
  float e = cov * cov * (3.0 - 2.0 * cov);
  half4 c = content.eval(xy);
  if (kind > 0.5 && kind < 1.5) {
    float mid = size.x * 0.5;
    float gap = mid * e;
    float sx = xy.x < mid ? xy.x + gap : xy.x - gap;
    bool hole = xy.x < mid ? sx >= mid : sx < mid;
    c = hole ? half4(half3(ink), 1.0) : content.eval(float2(sx, xy.y));
  } else if (kind > 1.5 && kind < 2.5) {
    float r = 0.045 * e * size.x;
    float4 acc = float4(c);
    float n = 1.0;
    for (int i = 0; i < 16; i++) {
      float fi = float(i);
      float a = fi * 2.39996;
      float d = r * sqrt((fi + 0.5) / 16.0);
      acc += float4(content.eval(xy + float2(cos(a), sin(a)) * d));
      n += 1.0;
    }
    float4 b = acc / n;
    c = half4(half3(mix(b.rgb, ink, 0.25 * e * e)), b.a);
  } else if (kind > 2.5) {
    float l = dot(float3(c.rgb), float3(0.2126, 0.7152, 0.0722));
    float thr = e * 1.1;
    float a = smoothstep(thr - 0.1, thr, l);
    c = half4(half3(mix(ink, float3(c.rgb), a)), c.a);
  }
  return c;
}
"""
}

/** The picture transitions on the made video: [at] says which and how far for each frame's time. */
@UnstableApi
internal class CutPictureEffect(private val at: (Long) -> PictureCuts.At?) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = CutPictureProgram(at)
}

@UnstableApi
private class CutPictureProgram(private val at: (Long) -> PictureCuts.At?) : BaseGlShaderProgram(false, 1) {
    private var width = 1
    private var height = 1
    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val a = at(presentationTimeUs)
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uKind", PictureCuts.code(a?.kind))
            program.setFloatUniform("uCov", a?.cov ?: 0f)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
            program.setFloatsUniform("uInk", floatArrayOf((Tok.INK shr 16 and 0xFF) / 255f, (Tok.INK shr 8 and 0xFF) / 255f, (Tok.INK and 0xFF) / 255f))
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
        const val VERTEX = CutPictureProgramShaders.VERTEX
        // Kind 0 passes the frame through. Split: each half slides out by up to half the width,
        // the gap dark. Blur: 16 taps on a spiral, reaching 4.5% of the width at the cut, a little
        // darker there. Luma: pixels darker than a rising level go dark first.
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uKind;
uniform float uCov;
uniform vec2 uTexel;
uniform vec3 uInk;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 uv = vTexSamplingCoord;
  float e = uCov * uCov * (3.0 - 2.0 * uCov);
  vec4 c = texture2D(uTexSampler, uv);
  if (uKind > 0.5 && uKind < 1.5) {
    float gap = 0.5 * e;
    float sx = uv.x < 0.5 ? uv.x + gap : uv.x - gap;
    bool hole = uv.x < 0.5 ? sx >= 0.5 : sx < 0.5;
    c = hole ? vec4(uInk, 1.0) : texture2D(uTexSampler, vec2(sx, uv.y));
  } else if (uKind > 1.5 && uKind < 2.5) {
    float r = 0.045 * e;
    vec4 acc = c;
    float n = 1.0;
    for (int i = 0; i < 16; i++) {
      float fi = float(i);
      float a = fi * 2.39996;
      float d = r * sqrt((fi + 0.5) / 16.0);
      acc += texture2D(uTexSampler, uv + vec2(cos(a), sin(a) * uTexel.y / uTexel.x) * d);
      n += 1.0;
    }
    c = acc / n;
    c.rgb = mix(c.rgb, uInk, 0.25 * e * e);
  } else if (uKind > 2.5) {
    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    float thr = e * 1.1;
    float a = smoothstep(thr - 0.1, thr, l);
    c.rgb = mix(uInk, c.rgb, a);
  }
  gl_FragColor = c;
}
"""
    }
}

/** The crop frame on the made video: the picture inside [box], dark outside it. */
@UnstableApi
internal class FrameMaskEffect(private val box: FrameBox) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FrameMaskProgram(box)
}

@UnstableApi
private class FrameMaskProgram(private val box: FrameBox) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = try {
        GlProgram(CutPictureProgramShaders.VERTEX, FRAGMENT).apply {
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
            // Texture y runs bottom-up; the frame is measured from the top.
            program.setFloatsUniform("uBox", floatArrayOf(box.left, 1f - box.bottom, box.right, 1f - box.top))
            program.setFloatsUniform("uInk", floatArrayOf((Tok.INK shr 16 and 0xFF) / 255f, (Tok.INK shr 8 and 0xFF) / 255f, (Tok.INK and 0xFF) / 255f))
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

    private companion object {
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec4 uBox;
uniform vec3 uInk;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 uv = vTexSamplingCoord;
  bool inside = uv.x >= uBox.x && uv.x <= uBox.z && uv.y >= uBox.y && uv.y <= uBox.w;
  gl_FragColor = inside ? texture2D(uTexSampler, uv) : vec4(uInk, 1.0);
}
"""
    }
}

/** The shared vertex shader (the whole frame, texture coordinates 0..1). */
internal object CutPictureProgramShaders {
    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
}
