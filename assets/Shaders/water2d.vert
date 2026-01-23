attribute vec4 a_position;
attribute vec4 a_color;
attribute vec2 a_texCoord0;

uniform mat4 u_projTrans;

varying vec2 v_world;     // world-space position in pixels
varying vec4 v_col;       // vertex color (alpha = fill)
varying vec2 v_uv;        // keep for local-in-tile math if wanted

void main() {
    v_world = a_position.xy;
    v_col   = a_color;
    v_uv    = a_texCoord0;

    gl_Position = u_projTrans * a_position;
}
