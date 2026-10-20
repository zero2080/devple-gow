#version 410 core
// 화면 사각형 (정점 버퍼 없이 gl_VertexID). vUv = 0..1 (아래 → 위, GL 텍스처 좌표).
out vec2 vUv;

void main() {
	vec2 p = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
	vUv = p * 0.5 + 0.5;
	gl_Position = vec4(p, 0.0, 1.0);
}
