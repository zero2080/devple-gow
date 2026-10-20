package devple.gow.tools.text;

import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 텍스트 모드 도구 (49 문서 §8).
 * <pre>
 *   text:export &lt;WAD&gt; &lt;out.properties&gt;   메시지 표 (원본, 모드 교체 전) 를 text/messages.properties 형식으로 저장
 * </pre>
 * 고칠 줄만 남겨 {@code mods/<m>/text/messages.properties} 에 두면 된다.
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 3 || !args[0].equals("export")) {
			System.err.println("usage: text:export <R_PERMK.WAD> <out.properties>");
			System.exit(1);
		}
		export(Path.of(args[1]), Path.of(args[2]));
	}

	static void export(Path wad, Path out) throws IOException {
		MessageTable t = MessageTable.fromWadRaw(WadFile.load(wad))
				.orElseThrow(() -> new IllegalArgumentException(wad + " 에 메시지 표(msgs*.txt) 없음 — R_PERM<지역>.WAD 를 주세요"));
		if (out.toAbsolutePath().getParent() != null) {
			Files.createDirectories(out.toAbsolutePath().getParent());
		}
		try (PrintStream o = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
			write(t, o);
		}
		System.out.println(out + ": " + t.all().size() + "개");
	}

	/** properties 형식 (UTF-8). 본문 줄바꿈은 \n, [CircleButton]·[*1] 태그와 ---- 페이지 구분은 그대로. */
	public static void write(MessageTable t, PrintStream o) {
		o.println("# " + MessageTable.OVERRIDE_FILE + " (49 문서 §8): <ID>=<본문>, 줄바꿈은 \\n. 바꿀 줄만 남기면 된다.");
		for (Map.Entry<Integer, String> e : t.all().entrySet()) {
			o.println(MessageTable.overrideLine(e.getKey(), e.getValue()));
		}
	}
}
