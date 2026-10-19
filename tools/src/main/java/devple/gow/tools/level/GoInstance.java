package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 게임 오브젝트 인스턴스 {@code go*} (CXT 서브타입 0x00020001, 0x5C 바이트). 레벨에 오브젝트(0x40001)를 하나 배치한다.
 * <pre>
 * 0x00 u32     magic 0x00020001
 * 0x04 char[24] 오브젝트 이름 (0x40001 리소스 이름, 소문자)
 * 0x1C u16     id (WAD 안 일련번호, EntityCount 와 연결)
 * 0x1E u16     flags (대부분 1, 0 = 비활성 시작 추정, 5)
 * 0x20 vec4    위치 (w=1)
 * 0x30 vec3    오일러 회전 (라디안, Rx·Ry·Rz) + w = 균일 스케일
 * 0x40 vec4    월드 경계 중심 (도구가 계산한 값, 배치에는 쓰지 않음)
 * 0x50 u32 0, u32 0, s32 -1
 * </pre>
 * 월드 행렬 = Scale(w) · Rx · Ry · Rz · Translate(pos) (행벡터). 오브젝트 관절 월드 = 관절 로컬 · 이 행렬.
 */
public record GoInstance(String name, String object, int id, int flags, float[] pos, float[] rot, float scale,
						 float[] bound) {

	public static final int MAGIC = 0x00020001;

	public static GoInstance parse(String name, ByteBuffer d) {
		byte[] nb = new byte[24];
		d.get(4, nb);
		int len = 0;
		while (len < 24 && nb[len] != 0) {
			len++;
		}
		float[] p = {d.getFloat(0x20), d.getFloat(0x24), d.getFloat(0x28)};
		float[] r = {d.getFloat(0x30), d.getFloat(0x34), d.getFloat(0x38)};
		float[] b = {d.getFloat(0x40), d.getFloat(0x44), d.getFloat(0x48)};
		return new GoInstance(name, new String(nb, 0, len, StandardCharsets.ISO_8859_1), d.getShort(0x1C) & 0xFFFF,
				d.getShort(0x1E) & 0xFFFF, p, r, d.getFloat(0x3C), b);
	}

	/** 인스턴스 월드 행렬 (행벡터, float[16]). */
	public float[] matrix() {
		float[] m = Mat4.fromEuler(rot[0], rot[1], rot[2]);
		for (int i = 0; i < 12; i++) {
			m[i] *= scale;
		}
		m[12] = pos[0];
		m[13] = pos[1];
		m[14] = pos[2];
		m[15] = 1;
		return m;
	}
}
