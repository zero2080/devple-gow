#version 410 core
// 그림자 (47 문서 §7.4): 볼륨 세기 패스는 색을 쓰지 않고, 어둡게 하기 패스 (uScreen) 는 uColor 를 역뺄셈 블렌드로 뺀다.
// 안개 (43 §31): 원작 그림자 합성 0x160518 은 층 2 안개 노드 (칸 5) 앞이라 안개가 그 위에 깔린다 — (1 − F)·(c − 38) + F·f.
// 엔진 지오메트리는 이미 셰이더 안에서 안개를 받았으므로 빼는 양을 (1 − F) 배 (안개 블렌드 0; 가산·감산 안개 (c − 38 ± F·f) 는 그대로).
#include "fog.glsl"
uniform vec4 uColor;
uniform int uShadowFog; // 1 = 장면 깊이 사본 (uSceneDepth, SceneFog.applyShadow) 의 안개 계수로 빼는 양을 줄인다
out vec4 fragColor;

void main() {
	vec4 c = uColor;
	if (uShadowFog != 0 && uFogMode != 0 && uFogBlend == 0) {
		float zw = texelFetch(uSceneDepth, ivec2(gl_FragCoord.xy), 0).r;
		float f = zw >= 1.0 ? 1.0 : clamp(fogAt(zw, -1.0).a, 0.0, 1.0);
		c.rgb *= 1.0 - f;
	}
	fragColor = c;
}
