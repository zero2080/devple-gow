#version 410 core
// SCR_LensFlare 스프라이트 (46 문서): 화면 정렬 사각형, 정점은 gl_VertexID 로 만든다.
uniform vec2 uCenter;   // NDC 중심
uniform vec2 uHalf;     // NDC 반폭
uniform float uZ;       // 빛의 NDC 깊이 (첫 패스 깊이 시험)
uniform float uRot;     // 머티리얼 애니 UV 회전 (라디안)

out vec2 vUv;

void main() {
	vec2 c = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
	float cs = cos(uRot), sn = sin(uRot);
	vec2 r = vec2(c.x * cs - c.y * sn, c.x * sn + c.y * cs);
	vUv = vec2(r.x * 0.5 + 0.5, 0.5 - r.y * 0.5);
	gl_Position = vec4(uCenter + c * uHalf, uZ, 1.0);
}
