#version 410 core
// MPEG-2 4:2:0 YUV (BT.601 제한 범위 16..235) → RGB
in vec2 vUv;
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform float uAlpha;
out vec4 fragColor;

void main() {
	float y = 1.164383 * (texture(uY, vUv).r - 16.0 / 255.0);
	float u = texture(uU, vUv).r - 128.0 / 255.0;
	float v = texture(uV, vUv).r - 128.0 / 255.0;
	vec3 rgb = vec3(y + 1.596027 * v, y - 0.391762 * u - 0.812968 * v, y + 2.017232 * u);
	fragColor = vec4(clamp(rgb, 0.0, 1.0), uAlpha);
}
