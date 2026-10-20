package devple.gow.tools.content;

import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WAD 태그 데이터 교체 (49 문서 §5). 계층의 {@code wad/<WAD이름>/<태그이름>.bin} 이 있으면, 그 이름의 데이터 태그
 * (같은 이름이 여럿이면 전부) 의 데이터를 파일 내용으로 바꾼 태그 스트림을 만든다. 크기가 달라도 되고 (헤더 크기를 고치고
 * 16 바이트 정렬로 채움), 나머지 태그는 바이트 그대로다. 텍스처·모델·애니메이션·스크립트·FLP(폰트 포함)·사운드 뱅크 등
 * WAD 안 리소스를 하나만 바꿀 때 쓴다 — 추출은 {@code tools wad:get}, 확인은 {@code wad:ls}.
 */
public final class WadPatches {

	private static final int HEADER = WadTag.HEADER, PARAM = WadTagType.PARAM, GROUP_START = WadTagType.GROUP_START, GROUP_END = WadTagType.GROUP_END;
	private static final Map<String, Map<String, Path>> CACHE = new ConcurrentHashMap<>();

	private WadPatches() {
	}

	static void clearCache() {
		CACHE.clear();
	}

	/** WAD 파일 이름 (예: ATHN01A.WAD) 의 태그 교체 표 (태그 이름 → 파일). 위 계층이 이긴다. */
	public static Map<String, Path> patchesFor(String wadFile) {
		String key = baseName(wadFile);
		return CACHE.computeIfAbsent(key, k -> {
			Map<String, Path> m = new HashMap<>();
			for (Path f : Content.listAll("wad/" + k)) {
				String n = f.getFileName().toString();
				if (n.toLowerCase().endsWith(".bin")) {
					m.put(n.substring(0, n.length() - 4), f);
				}
			}
			return Map.copyOf(m);
		});
	}

	/** 교체가 없으면 raw 를 그대로, 있으면 새 태그 스트림. */
	public static ByteBuffer apply(String wadFile, ByteBuffer raw) throws IOException {
		Map<String, Path> patches = patchesFor(wadFile);
		if (patches.isEmpty()) {
			return raw;
		}
		Map<String, byte[]> data = new HashMap<>();
		for (var e : patches.entrySet()) {
			data.put(e.getKey(), Files.readAllBytes(e.getValue()));
		}
		ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		ByteArrayOutputStream out = new ByteArrayOutputStream(b.limit() + 1024);
		int off = 0, replaced = 0;
		while (off + HEADER <= b.limit()) {
			int tag = Short.toUnsignedInt(b.getShort(off));
			int size = b.getInt(off + 4);
			String name = cstr(b, off + 8, 24);
			boolean hasData = tag != PARAM && size > 0;
			int next = tag == PARAM ? off + HEADER : off + HEADER + ((size + 15) & ~15);
			byte[] repl = hasData && tag != GROUP_START && tag != GROUP_END ? data.get(name) : null;
			if (repl == null) {
				byte[] chunk = new byte[Math.min(next, b.limit()) - off];
				b.get(off, chunk);
				out.writeBytes(chunk);
			} else {
				ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
				byte[] hdr = new byte[HEADER];
				b.get(off, hdr);
				h.put(hdr).putInt(4, repl.length);
				out.writeBytes(h.array());
				out.writeBytes(repl);
				out.writeBytes(new byte[((repl.length + 15) & ~15) - repl.length]);
				replaced++;
			}
			off = next;
		}
		if (off < b.limit()) {
			byte[] tail = new byte[b.limit() - off];
			b.get(off, tail);
			out.writeBytes(tail);
		}
		System.out.println("[mods] " + wadFile + ": 태그 " + replaced + "개 교체 " + patches.keySet());
		return ByteBuffer.wrap(out.toByteArray());
	}

	static String baseName(String file) {
		String n = file.toUpperCase();
		return n.endsWith(".WAD") ? n.substring(0, n.length() - 4) : n;
	}

	private static String cstr(ByteBuffer b, int off, int max) {
		int len = 0;
		while (len < max && b.get(off + len) != 0) {
			len++;
		}
		byte[] tmp = new byte[len];
		b.get(off, tmp);
		return new String(tmp, StandardCharsets.ISO_8859_1);
	}
}
