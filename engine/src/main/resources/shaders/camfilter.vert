#version 410 core
// 카메라 필터 화면 사각형 (정점 버퍼 없이 gl_VertexID, 45 문서 §1). vY = 0 화면 위 .. 1 화면 아래.
out float vY;

void main() {
	vec2 p = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
	vY = 0.5 - p.y * 0.5;
	gl_Position = vec4(p, 0.0, 1.0);
}
