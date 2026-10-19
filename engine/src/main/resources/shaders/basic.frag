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

out vec4 oColor;

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
	vec4 c = vColor * uTint;
	if (uUseTex != 0) {
		c *= texture(uTex, vUv);
	}
	if (c.a < (uAlphaRef > 0.0 ? uAlphaRef : 0.01) || (uAlphaMax > 0.0 && c.a >= uAlphaMax)) {
		discard;
	}
	if (uLit != 0) {
		vec3 n = normalize(vNormal);
		float d = max(dot(n, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
		c.rgb *= 0.35 + 0.65 * d;
	}
	if (uFogMode != 0) {
		c.rgb = applyFog(c.rgb);
	}
	oColor = c;
}
