package com.magicglass;

/**
 * AGSL source for the self-drawn liquid glass (0.4.0-p5).
 *
 * Adapted from big-dictator/LiquidTab (GlassHostLayout.kt): rounded-rect SDF +
 * refraction + optional chromatic dispersion. Kept as one shader so the engine
 * has a single uniform set: turning dispersion off is just
 * chromaticAberration = 0.
 *
 * IMPORTANT: this shader only *distorts* whatever is fed into `content`. The
 * real backdrop is drawn into a RenderNode first, blurred, then this effect is
 * chained on top -- exactly the `RenderEffect.createChainEffect(lens,
 * blurEffect)` order LiquidTab uses.
 */
final class LiquidShader {

    private LiquidShader() {
    }

    /** Signed distance helpers for a rounded rectangle. */
    private static final String SDF =
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) {\n" +
            "        if (coord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (coord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n";

    /**
     * Refraction + dispersion.
     *
     * Inside a band of `refractionHeight` px along the rounded edge, the sample
     * coordinate is pushed along the SDF gradient, which bends the backdrop
     * like a thick lens. Beyond that band it is a plain copy. Dispersion splits
     * R/G/B by a coordinate-dependent amount, producing the rainbow rim.
     *
     * 0.4.0-p17 DISPERSION RIM. `dispersion` peaks at 1.0 in the CORNERS and
     * the split is `d * grad * dispersion`, so on a 1174 x 1250 settings block
     * the rainbow used to fill the whole 35 px refraction band -- and inside
     * that band the lens already stretches the image by up to 91 px, so a
     * 8 px channel split got magnified into a solid cyan/yellow gash along the
     * bottom edge ("最下面的玻璃两边还是有明显的彩虹边"). `rim` confines the
     * split to the OUTERMOST ~1/3 of the band (12 px), where a real lens
     * actually disperses, and leaves the rest of the band colour-neutral.
     * EXACT reference behaviour is rim = 1.0; 0.0 removes the rainbow.
     */
    static final String GLASS =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            SDF +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(coord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) return content.eval(coord);\n" +
            "    float depthInBand = -sd;\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - depthInBand / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(\n" +
            "        gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +\n" +
            "        depthEffect * normalize(centeredCoord)\n" +
            "    );\n" +
            "    if (chromaticAberration <= 0.001) {\n" +
            "        return content.eval(coord + d * grad);\n" +
            "    }\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersion = chromaticAberration *\n" +
            "        ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "    float rim = clamp(1.0 - depthInBand / max(refractionHeight * 0.34, 1.0),\n" +
            "                      0.0, 1.0);\n" +
            "    float2 delta = d * grad * dispersion * rim;\n" +
            "    half4 color = half4(0.0);\n" +
            "    half4 red = content.eval(refractedCoord + delta);\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    half4 blue = content.eval(refractedCoord - delta);\n" +
            "    color.r = red.r;\n" +
            "    color.g = green.g;\n" +
            "    color.b = blue.b;\n" +
            "    color.a = max(red.a, max(green.a, blue.a));\n" +
            "    return color;\n" +
            "}\n";
}
