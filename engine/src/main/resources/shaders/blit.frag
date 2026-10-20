#version 410 core
// 거울 합성 (MirrorComposite): uUseTex = 1 이면 반해상도 버퍼를 쌍선형으로 (uOffset = 화면 비율 UV 이동), 아니면 단색.
in vec2 vUv;
uniform sampler2D uTex;
uniform int uUseTex;
uniform vec2 uOffset;
uniform vec4 uColor;
out vec4 fragColor;

void main() {
	fragColor = uUseTex != 0 ? vec4(texture(uTex, vUv + uOffset).rgb, 1.0) : uColor;
}
