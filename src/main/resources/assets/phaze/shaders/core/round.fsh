#version 150

uniform vec4 color1;
uniform vec4 color2;
uniform vec4 color3;
uniform vec4 color4;

uniform vec4 outlineColor;
uniform vec2 size;
uniform vec2 location;
uniform vec4 radius;
uniform float thickness;
uniform float softness;

out vec4 fragColor;

float roundedBoxSDF(vec2 center, vec2 size, vec4 radius) {
    radius.xy = (center.x > 0.0) ? radius.xy : radius.zw;
    radius.x  = (center.y > 0.0) ? radius.x : radius.y;

    vec2 q = abs(center) - size + radius.x;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius.x;
}

vec4 createGradient(vec2 coords, vec4 color1, vec4 color2, vec4 color3, vec4 color4){
    vec4 color = mix(mix(color1, color2, coords.y), mix(color3, color4, coords.y), coords.x);
    color.rgb += mix(0.0019607843, -0.0019607843, fract(sin(dot(coords.xy, vec2(12.9898, 78.233))) * 43758.5453));
    return color;
}

void main() {
    float distance = roundedBoxSDF(gl_FragCoord.xy - location - (size / 2.0), size / 2.0, radius);
    vec4 gradient = createGradient((gl_FragCoord.xy - location) / size, color1, color2, color3, color4);

    if (thickness <= 0.0) {
        float fillCoverage = 1.0 - smoothstep(-1.0, softness + 1.0, distance);
        fragColor = vec4(gradient.rgb, gradient.a * fillCoverage);
        return;
    }

    // The old abs(distance)/(thickness - 2) band faded outlines thinner than
    // two pixels even at the edge, then mixed coverage twice. Use the SDF's
    // screen-space gradient for an orientation-independent AA ramp and make
    // the outline the outer band of the shape's signed distance field.
    float distanceAA = length(vec2(dFdx(distance), dFdy(distance)));
    float aa = max(max(distanceAA, softness), 1.0E-4);
    float halfAa = aa * 0.5;
    float shapeCoverage = 1.0 - smoothstep(-halfAa, halfAa, distance);
    float fillCoverage = 1.0 - smoothstep(-thickness - halfAa, -thickness + halfAa, distance);
    float borderCoverage = max(shapeCoverage - fillCoverage, 0.0);

    // Composite disjoint fill/outline coverage, preserving transparent fills
    // while keeping the requested stroke width constant around rounded corners.
    float borderAlpha = outlineColor.a * borderCoverage;
    float fillAlpha = gradient.a * max(shapeCoverage - borderAlpha, 0.0);
    float outputAlpha = borderAlpha + fillAlpha;
    vec3 premultiplied = outlineColor.rgb * borderAlpha + gradient.rgb * fillAlpha;
    fragColor = vec4(outputAlpha > 0.0 ? premultiplied / outputAlpha : vec3(0.0), outputAlpha);
}

