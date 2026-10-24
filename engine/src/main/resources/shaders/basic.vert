#version 410 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;

uniform mat4 uModel;
uniform mat4 uViewProj;
uniform vec2 uUvOffset; // UV 스크롤 (설정 안 하면 0)
uniform int uEnvUv;     // 1 = 법선 기반 구면 환경맵 UV (레이어 rf bit 1 = VU 플래그 2, MaterialFx.ENV_FLAG)
uniform mat3 uEnvView;  // 월드 → 카메라 회전 (Renderer.envView): 원작 VU 0x0D9–0x0E3 은 법선을 모델·뷰 행렬 행으로 옮긴다
uniform vec4 uClipPlane; // SCR_Mirror 반사 패스: 월드 평면 (거울 뒤쪽 자름, GL_CLIP_DISTANCE0 켤 때만 쓰임)

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;
// 노멀맵 (원작에 없음, 44 문서): 월드 위치와 눈 위치 (uViewProj 역행렬 — 거울 패스면 반사된 눈)
uniform int uNmOn;
out vec3 vWorldPos;
out vec3 vEye;
// 동적 라이트 (47 문서): LightServer 0x1521B0 묶음 → VU1 조명 커널 (정점 단위, 255/128 로 자름). uLgtOn = 0 이면 예전 고정 조명
uniform int uLgtOn;
uniform vec3 uLgtDir0;
uniform vec3 uLgtDir1;
uniform vec3 uLgtCol0;
uniform vec3 uLgtCol1;
uniform vec3 uLgtAmb;
// 머티리얼 색 M (qw 76 = MAT 헤더 색 × 레이어 rgb × 물체 색): VU 커널은 M·128 로 라이트 합을 곱한 뒤 +1 편향, 255 로 자른다 (47 §3.1)
uniform vec3 uLgtMat;
uniform vec3 uLgtAdd;   // 더할 색 qw 77 / 128 (MAT 파일 0x18): vf29 = qw77 + 주변·M·128
out vec3 vLgt;

vec3 vuLight(vec3 n) {
	float d0 = max(dot(n, uLgtDir0), 0.0);
	float d1 = max(dot(n, uLgtDir1), 0.0);
	return min(uLgtAdd + uLgtMat * (uLgtAmb + uLgtCol0 * d0 + uLgtCol1 * d1) + 1.0 / 128.0, vec3(255.0 / 128.0));
}

void main() {
	vNormal = mat3(uModel) * aNormal;
	vLgt = vec3(1.0);
	if (uLgtOn != 0) {
		float ln = dot(vNormal, vNormal);
		vLgt = ln > 1e-12 ? vuLight(vNormal * inversesqrt(ln)) : min(uLgtAdd + uLgtMat * uLgtAmb + 1.0 / 128.0, vec3(255.0 / 128.0));
	}
	vUv = aUv + uUvOffset;
	if (uEnvUv != 0) {
		// 구면 환경맵: UV = 0.5 ± 0.5·(카메라 공간 법선).xy (VU 0x0D9: vf16–18.xy = 행 × −0.5/|행|, vf19.xy = 0.5) [확실: 구조 / 근사: 축 부호]
		vec3 nv = uEnvView * vNormal;
		if (dot(nv, nv) < 1e-12) {
			nv = vNormal;   // uEnvView 를 안 넣은 셰이더 상태 (예전 월드 법선)
		}
		nv = normalize(nv);
		vUv = vec2(0.5 + 0.5 * nv.x, 0.5 - 0.5 * nv.y) + uUvOffset;
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
