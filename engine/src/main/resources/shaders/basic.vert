#version 410 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;

uniform mat4 uModel;
uniform mat4 uViewProj;

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;

void main() {
	vNormal = mat3(uModel) * aNormal;
	vUv = aUv;
	vColor = aColor;
	gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
}
