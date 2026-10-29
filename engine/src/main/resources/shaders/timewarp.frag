#version 410 core
// 시간 왜곡 흐림 (원작 0x142A28): 앞 화면을 가장자리 uInset 만큼 안쪽 UV 로 (확대) 읽어 색을 곱함 (GS MODULATE, 255 에서 자름).
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uInset;
uniform vec3 uColor;
out vec4 oColor;

void main() {
	vec2 uv = uInset + vUv * (1.0 - 2.0 * uInset);
	vec3 c = texture(uTex, uv).rgb * uColor;
	oColor = vec4(min(c, vec3(1.0)), 1.0);
}
