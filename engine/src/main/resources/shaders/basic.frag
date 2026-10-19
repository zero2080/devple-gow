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
uniform vec4 uFogDepth;     // x = 안개 시작 거리 D0, y = near, z = far, w = 배경 알파 (BackPlane_A/128, <0 끔)
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
	vec4 fc = uFogMode == 2 ? layerColor(1.0 - gl_FragCoord.y / max(uFogScreen.x, 1.0)) : uFogColor;
	// GS Z = 16·CentreZ·n·(f-d)/((f-n)·d), 알파 = 1 - Z/2^16 ≈ 1 - D0/d (D0 = CentreZ·10/4096, 16 문서 §1.2)
	float k = uFogScreen.y > 0.5 && uFogDepth.w >= 0.0 ? uFogDepth.w : clamp(1.0 - uFogDepth.x / max(d, 1e-3), 0.0, 1.0);
	float a = fc.a * k;
	if (uFogTexOn != 0) {
		// 화면 공간 사각형: 중심 기준 배율·회전 후 스크롤 (GS MODULATE → ×2)
		vec2 uv = gl_FragCoord.xy / vec2(max(uFogWidth, 1.0), max(uFogScreen.x, 1.0)) - 0.5;
		float cs = cos(uFogTexXf.z), sn = sin(uFogTexXf.z);
		uv = mat2(cs, sn, -sn, cs) * uv / max(uFogTexXf.w, 1e-3) + 0.5 + uFogTexXf.xy;
		fc.rgb *= 2.0 * texture(uFogTex, uv).rgb;
	}
	if (uFogAdd != 0) {
		return c * (1.0 - a);
	}
	if (uFogBlend == 1) {
		return c + fc.rgb * a;
	}
	if (uFogBlend == 2) {
		return max(c - fc.rgb * a, 0.0);
	}
	return mix(c, fc.rgb, a);
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
