#version 410 core
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;

uniform sampler2D uTex;
uniform int uUseTex;
uniform int uLit;
uniform vec4 uTint;

out vec4 oColor;

void main() {
	vec4 c = vColor * uTint;
	if (uUseTex != 0) {
		c *= texture(uTex, vUv);
	}
	if (c.a < 0.01) {
		discard;
	}
	if (uLit != 0) {
		vec3 n = normalize(vNormal);
		float d = max(dot(n, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
		c.rgb *= 0.35 + 0.65 * d;
	}
	oColor = c;
}
