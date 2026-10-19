#version 410 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;

uniform mat4 uModel;
uniform mat4 uViewProj;
uniform vec2 uUvOffset; // UV 스크롤 (설정 안 하면 0)
uniform int uEnvUv;     // 1 = 법선 기반 구면 환경맵 UV (반사 레이어)
uniform vec4 uClipPlane; // SCR_Mirror 반사 패스: 월드 평면 (거울 뒤쪽 자름, GL_CLIP_DISTANCE0 켤 때만 쓰임)

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;
// 노멀맵 (원작에 없음, 44 문서): 월드 위치와 눈 위치 (uViewProj 역행렬 — 거울 패스면 반사된 눈)
uniform int uNmOn;
out vec3 vWorldPos;
out vec3 vEye;

void main() {
	vNormal = mat3(uModel) * aNormal;
	vUv = aUv + uUvOffset;
	if (uEnvUv != 0) {
		vec3 n = normalize(vNormal);
		vUv = n.xy * 0.5 + 0.5 + uUvOffset;
	}
	vColor = aColor;
	vec4 wp = uModel * vec4(aPos, 1.0);
	vWorldPos = wp.xyz;
	vEye = vec3(0.0);
	if (uNmOn != 0) {
		vec4 e = inverse(uViewProj) * vec4(0.0, 0.0, 1.0, 0.0);
		vEye = e.xyz / e.w;
	}
	gl_ClipDistance[0] = dot(wp, uClipPlane);
	gl_Position = uViewProj * wp;
}
