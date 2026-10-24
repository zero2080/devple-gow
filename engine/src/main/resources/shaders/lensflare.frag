#version 410 core
// 가산 (GL_ONE, GL_ONE): 텍스처 RGB × 세기 (첫 패스 ½, 가림 패스 1.5·보이는 비율·자름 비율).
in vec2 vUv;
uniform sampler2D uTex;
uniform float uScale;
out vec4 fragColor;

// 안개 (fog.glsl, SceneFog): 렌즈플레어 노드 (층 2 칸 5 키 0, 0x20E7D4) 는 안개 노드 (같은 칸 키 0x7100) 보다 먼저 → 가산 플레어도
// 그 화소 장면 깊이의 계수로 (1 − F) 배 (안개 블렌드 0). 안개 가산·감산 모드는 가산 플레어와 교환되므로 그대로.
#include "fog.glsl"
uniform int uFogPass;   // 1 = 장면 깊이 안개

void main() {
	// 회전한 UV 가 텍스처 밖이면 0 (가산이므로 그리지 않음과 같다) [근사: 원작 CLAMP 설정 미확인]
	if (any(lessThan(vUv, vec2(0.0))) || any(greaterThan(vUv, vec2(1.0)))) {
		discard;
	}
	vec3 c = texture(uTex, vUv).rgb * uScale;
	if (uFogPass != 0 && uFogMode != 0 && uFogBlend == 0) {
		c *= 1.0 - clamp(fogScene().a, 0.0, 1.0);
	}
	fragColor = vec4(c, 0.0);
}
