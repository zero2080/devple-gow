package devple.gow.tools.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SND 리소스 서브타입 4 (매직 0x40018) = 메모리 상주 VAG 팩 (예: R_PERMK 'Hero', R_MIN 'min').
 * DPMS kind 0 플러그인 메시지가 이름(확장자 제외)으로 항목을 재생한다.
 * <pre>
 * +0 u32 0x40018  +4 u32 N  +8 N × {char[24] "DIEVOC1.vag", u32 오프셋(리소스 시작 기준)}  이후 완전한 VAGp 파일들
 * </pre>
 */
public final class VagPack {

	public static final int MAGIC = 0x40018;

	private final String name;
	private final Map<String, byte[]> entries = new LinkedHashMap<>();

	public VagPack(String name, ByteBuffer data) {
		this.name = name;
		ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException("VAG 팩 아님: " + name);
		}
		int n = b.getInt(4);
		int[] offs = new int[n + 1];
		String[] names = new String[n];
		for (int i = 0; i < n; i++) {
			String nm = SoundBank.cstr(b, 8 + i * 28, 24);
			int dot = nm.lastIndexOf('.');
			names[i] = dot > 0 ? nm.substring(0, dot) : nm;
			offs[i] = b.getInt(8 + i * 28 + 24);
		}
		offs[n] = b.limit();
		for (int i = 0; i < n; i++) {
			int end = offs[i + 1] > offs[i] ? offs[i + 1] : b.limit();
			byte[] v = new byte[end - offs[i]];
			b.get(offs[i], v);
			entries.put(names[i].toUpperCase(), v);
		}
	}

	public static boolean isPack(ByteBuffer data) {
		return data.limit() >= 8 && data.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC;
	}

	public String name() {
		return name;
	}

	/** 항목 이름(대문자, 확장자 제외) → VAGp 파일 바이트. */
	public Map<String, byte[]> entries() {
		return Collections.unmodifiableMap(entries);
	}

	/** 이름(대소문자 무시)으로 디코딩. 없으면 null. */
	public AdpcmClip clip(String entry) {
		byte[] v = entries.get(entry.toUpperCase());
		return v == null ? null : vagClip(entry, v);
	}

	/** VAGp 파일(빅엔디언 48바이트 헤더) → 클립 (루프 플래그 반영). */
	public static AdpcmClip vagClip(String name, byte[] vag) {
		ByteBuffer b = ByteBuffer.wrap(vag).order(ByteOrder.BIG_ENDIAN);
		int size = Math.min(b.getInt(0x0C), vag.length - 0x30);
		return AdpcmClip.decode(name, b.getInt(0x10), vag, 0x30, size);
	}
}
