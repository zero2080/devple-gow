package devple.gow.tools.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * EMT 리소스 {@code SEM_*} (매직 0x1A, 224B) = 레벨 사운드 이미터.
 * <pre>
 * +0x00 u32 0x1A  +0x0A u16 (0xFFFF/0x0004 …)  +0x10 float[16] 로컬 행렬 (소유 GO 기준, +0x40 = 위치)
 * +0x50 char[24] × 4  소리 이름 (SND_*), SoundController Op 1..4 가 슬롯 선택
 * +0xD0 u32 8000(?)  +0xD4 u8 mode(0..3)  +0xD5..D7 u8 ?  +0xD8 float near  +0xDC float far   [근거리/원거리: 추정]
 * </pre>
 */
public record SoundEmitter(String name, List<String> sounds, float[] matrix, int mode, float near, float far) {

	public static final int MAGIC = 0x1A;

	public static boolean isEmitter(String resName, ByteBuffer data) {
		return resName.startsWith("SEM_") && data.limit() >= 0xE0 && data.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC;
	}

	public static SoundEmitter parse(String name, ByteBuffer data) {
		ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = b.getFloat(0x10 + i * 4);
		}
		List<String> snd = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			snd.add(SoundBank.cstr(b, 0x50 + i * 24, 24));
		}
		while (!snd.isEmpty() && snd.get(snd.size() - 1).isEmpty()) {
			snd.remove(snd.size() - 1);
		}
		return new SoundEmitter(name, List.copyOf(snd), m, b.get(0xD4) & 0xFF, b.getFloat(0xD8), b.getFloat(0xDC));
	}

	/** SoundController Op(1..4) 에 해당하는 소리 이름, 없으면 null. */
	public String sound(int op) {
		return op >= 1 && op <= sounds.size() && !sounds.get(op - 1).isEmpty() ? sounds.get(op - 1) : null;
	}
}
