#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_world;
varying vec4 v_col;
varying vec2 v_uv;

uniform float u_time;
uniform float u_tileH;        // tile height in pixels
uniform float u_worldHeight;  // map height in pixels

void main() {
    float fill = v_col.a; // 0..1 per drawn quad
    if (fill <= 0.001) discard;

    // --- cut off the quad so partial tiles don't look like full rectangles ---
    // localY = 0 at tile bottom, 1 at tile top
    float tileY  = floor(v_world.y / u_tileH);
    float localY = (v_world.y - tileY * u_tileH) / u_tileH;

    if (localY > fill) discard;

    // --- world-space waves (continuous across all tiles) ---
    float wx = v_world.x * 0.035;
    float wy = v_world.y * 0.035;

    float wave =
    sin(wy * 1.8 + u_time * 2.2) * 0.08 +
    sin(wx * 1.4 + u_time * 1.6) * 0.06;

    // --- depth based on absolute world Y (continuous) ---
    float depth01 = clamp(1.0 - (v_world.y / u_worldHeight), 0.0, 1.0);

    vec3 shallow = vec3(0.10, 0.55, 0.95);
    vec3 deep    = vec3(0.02, 0.25, 0.55);
    vec3 col = mix(shallow, deep, depth01);

    // surface highlight: strongest near the surface of each *tile fill*
    float surfaceDist = abs(localY - fill);
    float foam = smoothstep(0.12, 0.0, surfaceDist) * 0.18;

    col += foam;
    col += wave * 0.06; // subtle brightness modulation

    gl_FragColor = vec4(col, 0.85);
}
