#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_uv;
uniform sampler2D u_noise;
uniform float u_time;

void main() {
    vec2 uv = v_uv * 10.0 + vec2(u_time * 0.08, -u_time * 0.06);
    float f = texture2D(u_noise, uv).r;
    // make it “foamy”
    float foam = smoothstep(0.55, 0.85, f);
    gl_FragColor = vec4(vec3(1.0), foam);
}
