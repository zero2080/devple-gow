package devple.gow.tools.wad;

import devple.gow.tools.content.WadPatches;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 모드 제작용 WAD 태그 명령 (49 문서 §5, §15). 원본 파일 그대로 읽는다 (모드 계층의 태그 교체를 거치지 않음).
 * <pre>
 *   wad:get gow-rom/ATHN01A.WAD TXR_floor out.bin              태그 데이터 꺼내기 (= mods/&lt;m&gt;/wad/ATHN01A/TXR_floor.bin 형식)
 *   wad:put gow-rom/ATHN01A.WAD TXR_floor in.bin out.WAD       오프라인 교체 (엔진 적재 때와 같은 {@link WadPatches#rewrite})
 * </pre>
 */
public final class Cli {

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "get" -> {
				byte[] d = get(raw(Path.of(args[1])), args[2]);
				Files.write(Path.of(args[3]), d);
				System.out.printf("%s → %s (%d 바이트)%n", args[2], args[3], d.length);
			}
			case "put" -> {
				WadFile src = raw(Path.of(args[1]));
				byte[] in = Files.readAllBytes(Path.of(args[3]));
				ByteBuffer out = put(src, args[2], in);
				byte[] b = new byte[out.remaining()];
				out.duplicate().get(b);
				Files.write(Path.of(args[4]), b);
				System.out.printf("%s: %s 태그 %d 개 교체 (%d 바이트) → %s%n", args[1], args[2], dataTags(src, args[2]).size(), in.length, args[4]);
			}
			default -> throw new IllegalArgumentException("wad:<get|put> " + args[0]);
		}
	}

	/** 패치 없이 읽은 WAD. */
	public static WadFile raw(Path p) throws IOException {
		return new WadFile(p.getFileName().toString(), ByteBuffer.wrap(Files.readAllBytes(p)));
	}

	/** 교체 대상이 되는 태그 (데이터 있음, 그룹 시작·끝 아님) — {@link WadPatches#rewrite} 와 같은 규칙. */
	public static List<WadTag> dataTags(WadFile wad, String name) {
		return wad.tags().stream().filter(t -> name.equals(t.name()) && t.hasData() && t.tag() != WadTagType.GROUP_START
				&& t.tag() != WadTagType.GROUP_END).toList();
	}

	/** 태그 데이터 (같은 이름이 여럿이면 첫째, 내용이 다르면 경고). */
	public static byte[] get(WadFile wad, String name) {
		List<WadTag> ts = dataTags(wad, name);
		if (ts.isEmpty()) {
			throw new IllegalArgumentException(wad.name() + ": 데이터 태그 없음 " + name);
		}
		byte[] first = bytes(ts.getFirst());
		for (WadTag t : ts.subList(1, ts.size())) {
			if (!Arrays.equals(first, bytes(t))) {
				System.err.printf("주의: %s 가 %d 개이고 내용이 다름 — 첫째 (@%08x) 를 꺼냄. 교체는 전부 바꾼다%n", name, ts.size(), ts.getFirst().offset());
				break;
			}
		}
		return first;
	}

	/** 새 태그 스트림 (원본 WAD 는 그대로). */
	public static ByteBuffer put(WadFile wad, String name, byte[] data) {
		if (dataTags(wad, name).isEmpty()) {
			throw new IllegalArgumentException(wad.name() + ": 데이터 태그 없음 " + name);
		}
		return WadPatches.rewrite(wad.bytes(), Map.of(name, data));
	}

	private static byte[] bytes(WadTag t) {
		ByteBuffer d = t.data();
		byte[] b = new byte[d.remaining()];
		d.get(b);
		return b;
	}
}
