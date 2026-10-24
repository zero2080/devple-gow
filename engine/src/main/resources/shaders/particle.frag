#version 410 core
in vec2 vUv;
in vec4 vColor;
noperspective in vec2 vScr;

uniform sampler2D uTex;
uniform int uUseTex;
uniform int uSoft;      // 1 = 텍스처 없을 때 원형 부드러운 점
uniform int uGs;        // 1 = 원작 PTC (GS 규칙: 정점색 0x80 = 1.0, 알파 As/0x80, 미리 곱한 색 출력)
uniform int uTfx;       // TEX0.TFX: 0 MODULATE, 1 DECAL (2/3 HIGHLIGHT 는 MODULATE 로)
uniform int uTcc;       // TEX0.TCC: 1 = 텍스처 알파 사용
uniform int uScreen;    // 1 = 열기 아지랑이 (VU 종류 5): 프레임 복사본을 DECAL 로, 알파 = 정점 알파 (TCC 0)
uniform sampler2D uFrame;

// 안개 (fog.glsl, SceneFog): 원작 안개 패스 (층 2 칸 5, 0x1281C8) 는 층 2 버킷 0–5 파티클 (0x15DE50) 뒤에 프레임 전체를 장면 Z 의 계수로
// 안개색에 섞는다. 파티클은 깊이를 쓰지 않으므로 그 화소의 계수 = 뒤 장면 깊이의 계수. 엔진 지오메트리는 이미 셰이더 안에서 안개를 받았으니
// 파티클 자신만 같은 계수로 바꾸면 "파티클 → 안개" 와 같다: 안개색 섞기 M(x) = (1−F)·x + F·c 와 블렌드 (대상 계수 k) 의 교환
//   알파 (k = 1 − As): P' = (1−F)·P + F·c·As,  가산·감산 (k = 1): P' = (1−F)·P;  안개 가산·감산 모드 (M(x) = x ± F·c): 알파만 P' = P ± F·c·As.
#include "fog.glsl"
uniform int uFogPass;   // 0 없음 (버킷 6 · 층 3 · 근사 · 사슬/궤적), 1 장면 깊이 (층 2 버킷 0–5), 2 하늘 단계 (층 1, 하늘과 같은 배경 계수)
uniform int uFogDst;    // 1 = 알파 블렌드 (대상 계수 1 − As), 0 = 가산·감산 (대상 계수 1)

out vec4 oColor;

// 미리 곱한 색 (rgb·As, As) 에 안개
vec4 fogPremul(vec4 o) {
	if (uFogPass == 0 || uFogMode == 0) {
		return o;
	}
	vec4 fg = uFogPass == 2 ? fogAt(gl_FragCoord.z, uFogDepth.w >= 0.0 ? uFogDepth.w : -1.0) : fogScene();
	if (uFogBlend == 0) {
		float a = clamp(fg.a, 0.0, 1.0);
		o.rgb = o.rgb * (1.0 - a) + (uFogDst != 0 ? fg.rgb * (a * o.a) : vec3(0.0));
	} else if (uFogDst != 0) {
		o.rgb = clamp(o.rgb + (uFogBlend == 1 ? 1.0 : -1.0) * fg.rgb * (max(fg.a, 0.0) * o.a), 0.0, 1.0);
	}
	return o;
}

void main() {
	if (uScreen != 0) {
		float a = clamp(vColor.a, 0.0, 255.0 / 128.0);
		if (a < 0.004) {
			discard;
		}
		vec3 f = texture(uFrame, clamp(vScr, vec2(0.0), vec2(1.0))).rgb;
		oColor = vec4(f * min(a, 1.0), min(a, 1.0));
		return;
	}
	if (uGs != 0) {
		// GS: MODULATE Cs = Ct·Cv/0x80, As = At·Av/0x80 (TCC=0 이면 Av), 텍스처 없으면 Cs = Cv (0..255 → 0x80 은 회색 0.5)
		// 블렌드 계수 As/0x80 (최대 ≈ 2) → 색에 미리 곱해 내보내고, 블렌드 함수는 ONE / ONE_MINUS_SRC_ALPHA, ONE, REVERSE_SUBTRACT
		vec3 rgb;
		float a;
		if (uUseTex != 0) {
			vec4 t = texture(uTex, vUv);
			if (uTfx == 1) {
				rgb = t.rgb;
				a = uTcc != 0 ? t.a : vColor.a;
			} else {
				rgb = min(t.rgb * vColor.rgb, vec3(1.0));
				a = uTcc != 0 ? t.a * vColor.a : vColor.a;
			}
		} else {
			rgb = min(vColor.rgb * (128.0 / 255.0), vec3(1.0));
			a = vColor.a;
		}
		a = clamp(a, 0.0, 255.0 / 128.0);
		if (a < 0.004) {
			discard;
		}
		oColor = fogPremul(vec4(min(rgb * a, vec3(1.0)), min(a, 1.0)));
		return;
	}
	vec4 c = vColor;
	if (uUseTex != 0) {
		c *= texture(uTex, vUv);
	} else if (uSoft != 0) {
		vec2 d = vUv * 2.0 - 1.0;
		c.a *= clamp(1.0 - dot(d, d), 0.0, 1.0);
	}
	if (c.a < 0.004) {
		discard;
	}
	if (uFogPass != 0 && uFogMode != 0) {
		// 근사 경로 (블렌드 SRC_ALPHA, ONE_MINUS_SRC_ALPHA / ONE): 미리 곱한 꼴로 바꿔 같은 식
		float ca = min(c.a, 1.0);
		vec4 o = fogPremul(vec4(c.rgb * ca, ca));
		c.rgb = o.rgb / max(ca, 1e-4);
	}
	oColor = c;
}
