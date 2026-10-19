#version 410 core
// 가산 (GL_ONE, GL_ONE): 텍스처 RGB × 세기 (첫 패스 ½, 가림 패스 1.5·보이는 비율·자름 비율).
in vec2 vUv;
uniform sampler2D uTex;
uniform float uScale;
out vec4 fragColor;

void main() {
	// 회전한 UV 가 텍스처 밖이면 0 (가산이므로 그리지 않음과 같다) [근사: 원작 CLAMP 설정 미확인]
	if (any(lessThan(vUv, vec2(0.0))) || any(greaterThan(vUv, vec2(1.0)))) {
		discard;
	}
	vec3 c = texture(uTex, vUv).rgb * uScale;
	fragColor = vec4(c, 0.0);
}
