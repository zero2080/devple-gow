#version 410 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;

uniform mat4 uModel;
uniform mat4 uViewProj;
uniform vec2 uUvOffset; // UV 스크롤 (설정 안 하면 0)
uniform int uEnvUv;     // 1 = 법선 기반 구면 환경맵 UV (반사 레이어)

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;

void main() {
	vNormal = mat3(uModel) * aNormal;
	vUv = aUv + uUvOffset;
	if (uEnvUv != 0) {
		vec3 n = normalize(vNormal);
		vUv = n.xy * 0.5 + 0.5 + uUvOffset;
	}
	vColor = aColor;
	gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
}
