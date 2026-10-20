#version 410 core
// 그림자 볼륨 (47 문서 §7): uScreen = 1 이면 정점 버퍼 없이 화면 사각형 (어둡게 하기 패스).
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
uniform int uScreen;

void main() {
	if (uScreen != 0) {
		vec2 p = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
		gl_Position = vec4(p, 0.0, 1.0);
	} else {
		gl_Position = uViewProj * vec4(aPos, 1.0);
	}
}
