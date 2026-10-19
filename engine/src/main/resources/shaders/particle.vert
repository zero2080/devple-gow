#version 410 core
// 파티클 (31 문서): CPU 가 월드 좌표 사각형을 만들어 올린다.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;

uniform mat4 uViewProj;

out vec2 vUv;
out vec4 vColor;

void main() {
	vUv = aUv;
	vColor = aColor;
	gl_Position = uViewProj * vec4(aPos, 1.0);
}
