#version 410 core
// 파티클 (31 문서): CPU 가 월드 좌표 사각형을 만들어 올린다.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;

uniform mat4 uViewProj;
uniform int uScreen;    // 1 = 열기 아지랑이 (PTC VU 종류 5): UV = 정점 화면 위치 + aUv (화면 비율 오프셋)

out vec2 vUv;
out vec4 vColor;
noperspective out vec2 vScr;   // GS 처럼 화면 선형 보간

void main() {
	vUv = aUv;
	vColor = aColor;
	gl_Position = uViewProj * vec4(aPos, 1.0);
	vScr = uScreen != 0 ? gl_Position.xy / gl_Position.w * 0.5 + 0.5 + aUv : vec2(0.0);
}
