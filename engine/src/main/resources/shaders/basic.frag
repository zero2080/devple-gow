#version 410 core
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;

uniform sampler2D uTex;
uniform int uUseTex;
uniform int uLit;
uniform vec4 uTint;

// 안개 (16/31 문서): 유니폼·계수는 fog.glsl. 유니폼을 설정하지 않으면 0 = 끔 → 기존 동작과 같다.
#include "fog.glsl"
uniform float uAlphaRef;    // 알파 테스트 기준 (0 = 기본 0.01)
uniform float uAlphaMax;    // > 0 이면 알파가 이 값 이상인 픽셀 버림 (CUTOUT 가장자리 패스)
uniform int uFogAdd;        // 1 = 가산 블렌드 파트: 안개만큼 기여를 줄인다
uniform int uFogScene;      // 1 = 조각 깊이 대신 장면 깊이 사본으로 계수 (깊이를 쓰지 않는 이펙트 GO 반투명 파트·물, SceneFog)
uniform int uFogOff;        // 1 = 이 파트는 안개 뒤 (층 3·4 머티리얼, 이펙트 GO) — 안개 없음

// 노멀맵 (원작에 없음, 44 문서). uNmOn = 0 이면 아래 경로를 전혀 타지 않아 원래 화면과 같다.
in vec3 vWorldPos;
in vec3 vEye;
uniform int uNmOn;
uniform sampler2D uNormalMap;   // 유닛 2, 탄젠트 공간 (+X = +u, +Y = +v)
uniform vec3 uNmDetail;         // 구운 메시 세부 계수: x = k, y = 하한, z = 상한

// 동적 라이트 (47 문서): 정점 셰이더가 VU1 조명 커널 식으로 구한 배율. uLgtOn = 1 이면 정점 색 대신 이것 (원작 조명 커널은 메시 정점 색을 읽지 않는다)
in vec3 vLgt;
uniform int uLgtOn;
uniform vec3 uLgtDir0;
uniform vec3 uLgtDir1;
uniform vec3 uLgtCol0;
uniform vec3 uLgtCol1;
uniform vec3 uLgtAmb;
uniform vec3 uLgtMat;   // 머티리얼 색 M (47 §3.1): 동적 라이트 경로에서는 uTint.rgb 대신 이것이 색 배율
uniform vec3 uLgtAdd;   // 더할 색 qw 77 / 128

out vec4 oColor;

// 화면 미분 코탄젠트 좌표계 (탄젠트 저장 없음): N 기준으로 dp/duv 를 직교화, UV 거울·회전도 그대로 반영된다
vec3 nmPerturb(vec3 N, vec3 dp1, vec3 dp2, vec2 du1, vec2 du2, vec3 m) {
	vec3 dp2perp = cross(dp2, N);
	vec3 dp1perp = cross(N, dp1);
	vec3 T = dp2perp * du1.x + dp1perp * du2.x;
	vec3 B = dp2perp * du1.y + dp1perp * du2.y;
	float tb = max(dot(T, T), dot(B, B));
	if (tb < 1e-20) {
		return N; // UV 가 퇴화 (한 점에 몰림)
	}
	float inv = inversesqrt(tb);
	return normalize(mat3(T * inv, B * inv, N) * m);
}

vec3 applyFog(vec3 c) {
	// 지오메트리: 조각 자신의 깊이 (배경 그리기 중이면 BackPlane). uFogScene: 장면 깊이 사본 (fog.glsl fogScene)
	vec4 fg = uFogScene != 0 ? fogScene() : fogAt(gl_FragCoord.z, uFogScreen.y > 0.5 && uFogDepth.w >= 0.0 ? uFogDepth.w : -1.0);
	float a = fg.a;
	if (uFogAdd != 0) {
		return c * (1.0 - min(a, 1.0));
	}
	if (uFogBlend == 1) {
		return min(c + fg.rgb * a, 1.0);
	}
	if (uFogBlend == 2) {
		return max(c - fg.rgb * a, 0.0);
	}
	// GS (Cs−Cd)·Ad + Cd, Ad 는 1 을 넘을 수 있다 (COLCLAMP 로 0..1)
	return clamp(mix(c, fg.rgb, a), 0.0, 1.0);
}

void main() {
	// 노멀맵 미분은 discard 전에 (균일 분기 안)
	float nmFactor = 1.0;
	vec3 nmN = vec3(0.0);
	bool nmLitN = false;
	if (uNmOn != 0) {
		vec3 dp1 = dFdx(vWorldPos), dp2 = dFdy(vWorldPos);
		vec2 du1 = dFdx(vUv), du2 = dFdy(vUv);
		vec3 m = texture(uNormalMap, vUv).xyz * 2.0 - 1.0;
		vec3 V = normalize(vEye - vWorldPos);
		if (uLit != 0) {
			// 법선 있는 메시 (캐릭터·조명 파트): 기존 법선을 흔들어 아래 기존 조명식에 넣는다
			vec3 n0 = vNormal;
			if (dot(n0, n0) < 1e-8) {
				n0 = cross(dp1, dp2);
			}
			nmN = nmPerturb(normalize(n0), dp1, dp2, du1, du2, m);
			nmLitN = true;
		} else {
			// 조명 구운 레벨 메시: 보이는 쪽 면 법선 (감김 무관, 거울 패스도 같음) → 부드러운 법선을 그쪽으로 맞춤
			vec3 nf = cross(dp1, dp2);
			float lf = dot(nf, nf);
			if (lf > 1e-20) {
				nf *= inversesqrt(lf);
				if (dot(nf, V) < 0.0) {
					nf = -nf;
				}
				vec3 ng = nf;
				if (dot(vNormal, vNormal) > 0.25) {
					vec3 ns = normalize(vNormal);
					ns = dot(ns, nf) < 0.0 ? -ns : ns;
					ng = dot(ns, nf) > 0.5 ? ns : nf; // 모서리 (평균이 면과 많이 다름) 는 면 법선
				}
				vec3 nm = nmPerturb(ng, dp1, dp2, du1, du2, m);
				vec3 L = normalize(V + vec3(0.0, 0.8, 0.0)); // 카메라 위쪽에서 비추는 고정 방향 (레벨 광원 데이터 미사용, 44 문서)
				nmFactor = clamp(1.0 + uNmDetail.x * (dot(nm, L) - dot(ng, L)), uNmDetail.y, uNmDetail.z);
			}
		}
	}
	bool dyn = uLit != 0 && uLgtOn != 0;
	// 동적 라이트: 색 = 텍스처 × min(M·L + 1/128, 255/128) (M 은 정점 셰이더 vLgt 에 이미 곱함), 알파는 정점 α × uTint.a
	vec4 c = dyn ? vec4(1.0, 1.0, 1.0, vColor.a * uTint.a) : vColor * uTint;
	if (uUseTex != 0) {
		c *= texture(uTex, vUv);
	}
	if (c.a < (uAlphaRef > 0.0 ? uAlphaRef : 0.01) || (uAlphaMax > 0.0 && c.a >= uAlphaMax)) {
		discard;
	}
	c.rgb *= nmFactor;
	if (dyn) {
		vec3 lg = vLgt;
		if (nmLitN) {
			// 노멀맵 켜짐: 흔든 법선으로 화소 단위 (원작에 없음, 44 문서)
			lg = min(uLgtAdd + uLgtMat * (uLgtAmb + uLgtCol0 * max(dot(nmN, uLgtDir0), 0.0) + uLgtCol1 * max(dot(nmN, uLgtDir1), 0.0)) + 1.0 / 128.0,
					vec3(255.0 / 128.0));
		}
		c.rgb *= lg;
	} else if (uLit != 0) {
		vec3 n = nmLitN ? nmN : normalize(vNormal);
		float d = max(dot(n, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
		c.rgb *= 0.35 + 0.65 * d;
	}
	if (uFogMode != 0 && uFogOff == 0) {
		c.rgb = applyFog(c.rgb);
	}
	oColor = c;
}
