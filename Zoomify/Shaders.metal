#include <metal_stdlib>
using namespace metal;

// Must match `PreviewParams` in PreviewRenderer.swift.
struct PreviewParams {
    float2 viewSize;
    float2 cropScale;
    float2 cropOffset;
    float  blurAmount;    // 0 = sharp, 0.14 = streaks over 14% of the radius
    float  historyMix;    // weight of the previous frame (temporal blur / lens-switch cross-fade)
    float  historyScale;  // previous zoom / current zoom, aligns the previous frame with the current one
    float  codeScale;     // unorm sample -> integer code value
    float  yOffset;
    float  yRange;
    float  cOffset;
    float  cRange;
    float  kr;
    float  kb;
    uint   rotation;      // 0 / 90 / 180 / 270, clockwise
    uint   mirror;
    uint   transfer;      // 0 = SDR (BT.709 / sRGB-style), 1 = HLG
    uint   sampleCount;
    float  seed;
};

constexpr sampler linearSampler(coord::normalized, address::clamp_to_edge, filter::linear);

// View coordinates (0..1, top-left origin, portrait) -> sensor buffer coordinates (landscape).
static float2 viewToBuffer(float2 p, constant PreviewParams& P) {
    float2 r = P.cropOffset + p * P.cropScale;
    if (P.mirror != 0) { r.x = 1.0 - r.x; }
    switch (P.rotation) {
        case 90:  return float2(r.y, 1.0 - r.x);
        case 180: return float2(1.0 - r.x, 1.0 - r.y);
        case 270: return float2(1.0 - r.y, r.x);
        default:  return r;
    }
}

static float3 sdrToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, 2.4);
    return select(hi, lo, c <= 0.04045);
}

// ITU-R BT.2100 HLG: inverse OETF + OOTF for a 1000-nit display, normalised so HLG reference white
// (75% signal, 203 nits) == 1.0. Values above 1.0 are shown as EDR highlights.
static float3 hlgToLinear(float3 e) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    float3 lo = (e * e) / 3.0;
    float3 hi = (exp((e - c) / a) + b) / 12.0;
    float3 scene = select(hi, lo, e <= 0.5);
    float ys = dot(scene, float3(0.2627, 0.6780, 0.0593));
    float gain = 1000.0 * pow(max(ys, 1e-6), 0.2) / 203.0;
    return scene * gain;
}

static float3 sampleLinear(texture2d<float, access::sample> lumaT,
                           texture2d<float, access::sample> chromaT,
                           float2 uv,
                           constant PreviewParams& P) {
    float y = lumaT.sample(linearSampler, uv).r * P.codeScale;
    float2 c = chromaT.sample(linearSampler, uv).rg * P.codeScale;
    float Y = (y - P.yOffset) / P.yRange;
    float2 C = (c - P.cOffset) / P.cRange;          // (Cb, Cr) in -0.5 ... 0.5
    float r = Y + 2.0 * (1.0 - P.kr) * C.y;
    float b = Y + 2.0 * (1.0 - P.kb) * C.x;
    float g = (Y - P.kr * r - P.kb * b) / (1.0 - P.kr - P.kb);
    float3 rgb = clamp(float3(r, g, b), 0.0, 1.0);
    return P.transfer == 1 ? hlgToLinear(rgb) : sdrToLinear(rgb);
}

kernel void zoomBlurPreview(texture2d<float, access::sample> lumaT     [[texture(0)]],
                            texture2d<float, access::sample> chromaT   [[texture(1)]],
                            texture2d<float, access::sample> historyIn [[texture(2)]],
                            texture2d<half,  access::write>  output    [[texture(3)]],
                            texture2d<float, access::write>  historyOut [[texture(4)]],
                            constant PreviewParams& P [[buffer(0)]],
                            uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) { return; }

    const float2 center = float2(0.5, 0.5);
    float2 p = (float2(gid) + 0.5) / P.viewSize;
    float3 color;

    if (P.sampleCount <= 1 || P.blurAmount <= 0.0) {
        color = sampleLinear(lumaT, chromaT, viewToBuffer(p, P), P);
    } else {
        // Radial (zoom) blur in linear light: sample along the ray towards the centre,
        // jittered per pixel so a handful of taps looks like a continuous streak.
        float noise = fract(52.9829189 * fract(dot(float2(gid), float2(0.06711056, 0.00583715))) + P.seed);
        float3 acc = float3(0.0);
        float weightSum = 0.0;
        for (uint i = 0; i < P.sampleCount; ++i) {
            float t = (float(i) + noise) / float(P.sampleCount);
            float scale = 1.0 - P.blurAmount * t;
            float w = 1.0 - 0.5 * t;
            float2 q = center + (p - center) * scale;
            acc += sampleLinear(lumaT, chromaT, viewToBuffer(q, P), P) * w;
            weightSum += w;
        }
        color = acc / weightSum;
    }

    if (P.historyMix > 0.0) {
        float2 hp = center + (p - center) * P.historyScale;
        float3 previous = historyIn.sample(linearSampler, hp).rgb;
        color = mix(color, previous, P.historyMix);
    }

    output.write(half4(half3(color), 1.0h), gid);
    historyOut.write(float4(color, 1.0), gid);
}
