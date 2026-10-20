package devple.gow.tools.pak;

import devple.gow.tools.iso.IsoEntry;
import devple.gow.tools.iso.IsoImage;
import devple.gow.tools.iso.SystemCnf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 갓오브워 디스크 = ISO + 목차 (*.TOC, 한글판 GODOFWAR.TOC) + PART&lt;n&gt;.PAK (한글판 2층 DVD9: PART1 레이어 0, PART2 레이어 1).
 * 게임 데이터 파일(WAD/VAG/VPK/PSS ...)에 이름으로 접근한다. 판마다 다를 수 있는 것 (49 문서 §15): 목차 이름 (GODOFWAR.TOC 우선, 없으면
 * 처음 만난 *.TOC), PAK 개수 (PART1..PART9, 1층 디스크는 PART1 만), 위치 (레이어·디렉터리 무관, 파일 이름으로 찾음). 부트 실행 파일은 {@link #systemCnf}.
 */
public final class GowDisc implements AutoCloseable {

	/** 한글판 목차 이름 (다른 이름의 *.TOC 보다 우선). */
	public static final String TOC = "GODOFWAR.TOC";
	private static final Pattern PART = Pattern.compile("PART([1-9])\\.PAK");

	private final IsoImage iso;
	private final GowToc toc;
	private final IsoEntry[] parts = new IsoEntry[9];
	private final IsoEntry tocEntry;
	private final SystemCnf cnf;

	public GowDisc(Path isoPath) throws IOException {
		this.iso = new IsoImage(isoPath);
		IsoEntry toc = null;
		for (IsoEntry e : iso.listAll()) {
			if (e.dir()) {
				continue;
			}
			String n = e.path().substring(e.path().lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
			Matcher m = PART.matcher(n);
			if (m.matches() && parts[m.group(1).charAt(0) - '1'] == null) {
				parts[m.group(1).charAt(0) - '1'] = e;
			} else if (n.endsWith(".TOC") && (toc == null || n.equals(TOC))) {
				toc = e;
			}
		}
		if (toc == null || parts[0] == null) {
			throw new IOException("목차(*.TOC) / PART1.PAK 를 찾을 수 없음 — 갓오브워 디스크가 아님?");
		}
		this.tocEntry = toc;
		this.toc = new GowToc(iso.read(toc, 0, (int) toc.size()));
		this.cnf = SystemCnf.read(iso);
	}

	public IsoImage iso() {
		return iso;
	}

	public GowToc toc() {
		return toc;
	}

	/** 목차 파일 이름 (GODOFWAR.TOC). */
	public String tocName() {
		return tocEntry.path().substring(tocEntry.path().lastIndexOf('/') + 1);
	}

	/** SYSTEM.CNF (부트 실행 파일·영상 방식). 없으면 null. */
	public SystemCnf systemCnf() {
		return cnf;
	}

	/** 있는 PAK (PART1.PAK …). */
	public List<String> parts() {
		List<String> out = new ArrayList<>();
		for (IsoEntry p : parts) {
			if (p != null) {
				out.add(p.path().substring(1) + "@L" + p.layer());
			}
		}
		return out;
	}

	public List<TocEntry> entries() {
		return toc.entries();
	}

	public ByteBuffer read(TocEntry e) throws IOException {
		return iso.read(partOf(e), e.sector() * IsoImage.SECTOR, Math.toIntExact(e.size()));
	}

	/** 파일 내 오프셋에서 일부만 읽는다 (동영상 스트리밍). FileChannel 위치 지정 읽기라 스레드 안전. */
	public ByteBuffer read(TocEntry e, long offset, int length) throws IOException {
		return iso.read(partOf(e), e.sector() * IsoImage.SECTOR + offset, length);
	}

	public void copyTo(TocEntry e, OutputStream os) throws IOException {
		IsoEntry part = partOf(e);
		final int chunk = 1 << 20;
		long base = e.sector() * IsoImage.SECTOR;
		for (long off = 0; off < e.size(); off += chunk) {
			ByteBuffer b = iso.read(part, base + off, (int) Math.min(chunk, e.size() - off));
			os.write(b.array(), 0, b.limit());
		}
	}

	private IsoEntry partOf(TocEntry e) throws IOException {
		IsoEntry p = e.part() >= 0 && e.part() < parts.length ? parts[e.part()] : null;
		if (p == null) {
			throw new IOException("PART" + (e.part() + 1) + ".PAK 없음: " + e.name());
		}
		return p;
	}

	@Override
	public void close() throws IOException {
		iso.close();
	}
}
