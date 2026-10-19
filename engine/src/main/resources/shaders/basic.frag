#version 410 core
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;

uniform sampler2D uTex;
uniform int uUseTex;
uniform int uLit;
uniform vec4 uTint;

// 안개 (16/31 문서). 유니폼을 설정하지 않으면 0 = 끔 → 기존 동작과 같다.
uniform int uFogMode;       // 0 끔, 1 SCR_Fog, 2 SCR_LayeredFog
uniform int uFogBlend;      // 0 블렌드, 1 가산, 2 감산 (GS 0x54 / 0x58 / 0x52)
uniform vec4 uFogColor;     // 단일 안개 색 (a = 최대 불투명도) / 층 안개 색 0
uniform vec4 uFogColor1;    // 층 안개 색 1
uniform vec4 uFogColor2;    // 층 안개 색 2
uniform vec3 uFogStops;     // 층 안개 색 위치 (화면 위 0 → 아래 1)
uniform vec4 uFogDepth;     // x = 16·CentreZ·n0 (원작 Z 배율), y = near, z = far (선형화용), w = 배경 알파 (BackPlane_A/128, <0 끔)
uniform vec2 uFogGs;        // 원작 뷰포트 far f0, near n0 (Z24 = 0x800000 + z', z' = x·(f0−d)/((f0−n0)·d))
uniform vec2 uFogBand;      // 층 안개: 화면 위/아래의 고도각 눈금 t (원작 0x1208C8, 지평선 0.5). 같으면 화면 높이 비율로
uniform vec2 uFogScreen;    // 프레임버퍼 높이 (px), 배경 그리기 중이면 y = 1
uniform float uAlphaRef;    // 알파 테스트 기준 (0 = 기본 0.01)
uniform float uAlphaMax;    // > 0 이면 알파가 이 값 이상인 픽셀 버림 (CUTOUT 가장자리 패스)
uniform int uFogAdd;
uniform sampler2D uFogTex;  // 안개 텍스처 (유닛 1, MAT_defFogMat 등)
uniform int uFogTexOn;
uniform vec4 uFogTexXf;     // 스크롤 u, v (누적), 회전 (라디안, 누적), 배율
uniform float uFogWidth;    // 프레임버퍼 폭 (px)        // 1 = 가산 블렌드 파트: 안개만큼 기여를 줄인다

// 노멀맵 (원작에 없음, 44 문서). uNmOn = 0 이면 아래 경로를 전혀 타지 않아 원래 화면과 같다.
in vec3 vWorldPos;
in vec3 vEye;
uniform int uNmOn;
uniform sampler2D uNormalMap;   // 유닛 2, 탄젠트 공간 (+X = +u, +Y = +v)
uniform vec3 uNmDetail;         // 구운 메시 세부 계수: x = k, y = 하한, z = 상한

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

vec4 layerColor(float t) {
	if (t <= uFogStops.x) {
		return uFogColor;
	}
	if (t <= uFogStops.y) {
		return mix(uFogColor, uFogColor1, (t - uFogStops.x) / max(uFogStops.y - uFogStops.x, 1e-4));
	}
	if (t <= uFogStops.z) {
		return mix(uFogColor1, uFogColor2, (t - uFogStops.y) / max(uFogStops.z - uFogStops.y, 1e-4));
	}
	return uFogColor2;
}

vec3 applyFog(vec3 c) {
	float n = uFogDepth.y, f = uFogDepth.z;
	float zn = gl_FragCoord.z * 2.0 - 1.0;
	float d = 2.0 * n * f / (f + n - zn * (f - n));
	float sy = 1.0 - gl_FragCoord.y / max(uFogScreen.x, 1.0);
	vec4 fc = uFogMode == 2 ? layerColor(uFogBand.x != uFogBand.y ? mix(uFogBand.x, uFogBand.y, sy) : sy) : uFogColor;
	// 원작 안개 패스 (16 문서 §1.2): Z24 = 0x800000 + z' → 0x127918 이 알파 A8 = 128 − ⌊z'/256⌋ (z' < 256 은 127),
	// 안개색 스프라이트(0x127000)는 Z = 0x807FFF GREATER 검사라 z' ≥ 0x7FFF (가까운 화소) 에는 안 그린다.
	// 계수 = A8·Fog_A / 0x80 (0x126CF8 변조, 0x54 블렌드) ≈ Fog_A·(1 − z'/32768).
	float f0 = max(uFogGs.x, uFogGs.y + 1.0), n0 = max(uFogGs.y, 1e-3);
	float zp = uFogDepth.x * max(f0 - d, 0.0) / ((f0 - n0) * max(d, 1e-3));
	float g = floor(zp / 256.0);
	float a8 = zp >= 32767.0 ? 0.0 : (g >= 1.0 ? 128.0 - g : 127.0);
	float k = uFogScreen.y > 0.5 && uFogDepth.w >= 0.0 ? uFogDepth.w : a8 / 128.0;
	float a = fc.a * k;
	if (uFogTexOn != 0) {
		// 화면 공간 사각형: 중심 기준 배율·회전 후 스크롤 (GS MODULATE → ×2)
		vec2 uv = gl_FragCoord.xy / vec2(max(uFogWidth, 1.0), max(uFogScreen.x, 1.0)) - 0.5;
		float cs = cos(uFogTexXf.z), sn = sin(uFogTexXf.z);
		uv = mat2(cs, sn, -sn, cs) * uv / max(uFogTexXf.w, 1e-3) + 0.5 + uFogTexXf.xy;
		fc.rgb *= 2.0 * texture(uFogTex, uv).rgb;
	}
	if (uFogAdd != 0) {
		return c * (1.0 - min(a, 1.0));
	}
	if (uFogBlend == 1) {
		return min(c + fc.rgb * a, 1.0);
	}
	if (uFogBlend == 2) {
		return max(c - fc.rgb * a, 0.0);
	}
	// GS (Cs−Cd)·Ad + Cd, Ad 는 1 을 넘을 수 있다 (COLCLAMP 로 0..1)
	return clamp(mix(c, fc.rgb, a), 0.0, 1.0);
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
	vec4 c = vColor * uTint;
	if (uUseTex != 0) {
		c *= texture(uTex, vUv);
	}
	if (c.a < (uAlphaRef > 0.0 ? uAlphaRef : 0.01) || (uAlphaMax > 0.0 && c.a >= uAlphaMax)) {
		discard;
	}
	c.rgb *= nmFactor;
	if (uLit != 0) {
		vec3 n = nmLitN ? nmN : normalize(vNormal);
		float d = max(dot(n, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
		c.rgb *= 0.35 + 0.65 * d;
	}
	if (uFogMode != 0) {
		c.rgb = applyFog(c.rgb);
	}
	oColor = c;
}
