#version 410 core
// 동영상 사각형 (정점 버퍼 없이 gl_VertexID 로 생성, 33 문서). 위치는 glViewport 로 레터박스.
out vec2 vUv;

void main() {
	vec2 p = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
	vUv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);   // 프레임 0행 = 화면 위
	gl_Position = vec4(p, 0.0, 1.0);
}
