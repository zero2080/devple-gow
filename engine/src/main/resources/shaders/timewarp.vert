#version 410 core
// 시간 왜곡 흐림 화면 사각형 (정점 버퍼 없이 gl_VertexID, 43 §47.2). vUv = 0..1 (GL 화면 좌표와 같은 방향).
out vec2 vUv;

void main() {
	vec2 p = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
	vUv = p * 0.5 + 0.5;
	gl_Position = vec4(p, 0.0, 1.0);
}
