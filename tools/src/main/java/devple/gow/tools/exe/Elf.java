package devple.gow.tools.exe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PS2 EE 실행 파일(ELF32 MIPS little endian) 로더.
 * PT_LOAD 세그먼트를 가상 주소 공간으로 매핑하고, 섹션 헤더로 .text/.data/.rodata 범위를 제공한다.
 */
public final class Elf {

	/** 섹션 헤더 (이름, 가상 주소, 크기, 파일 오프셋). */
	public record Section(String name, int addr, int size, int offset, int type) {
		public boolean contains(int va) {
			return addr != 0 && Integer.compareUnsigned(va - addr, size) < 0;
		}
	}

	private final ByteBuffer file;
	private final int entry;
	private final int loadAddr;
	private final int loadOffset;
	private final int fileSize;
	private final int memSize;
	private final List<Section> sections = new ArrayList<>();

	private Elf(ByteBuffer b) {
		this.file = b.order(ByteOrder.LITTLE_ENDIAN);
		entry = b.getInt(0x18);
		int phoff = b.getInt(0x1C);
		int shoff = b.getInt(0x20);
		int phentsize = Short.toUnsignedInt(b.getShort(0x2A));
		int phnum = Short.toUnsignedInt(b.getShort(0x2C));
		int shentsize = Short.toUnsignedInt(b.getShort(0x2E));
		int shnum = Short.toUnsignedInt(b.getShort(0x30));
		int shstrndx = Short.toUnsignedInt(b.getShort(0x32));
		int la = 0, lo = 0, fs = 0, ms = 0;
		for (int i = 0; i < phnum; i++) {
			int p = phoff + i * phentsize;
			if (b.getInt(p) == 1) {
				lo = b.getInt(p + 4);
				la = b.getInt(p + 8);
				fs = b.getInt(p + 16);
				ms = b.getInt(p + 20);
				break;
			}
		}
		loadAddr = la;
		loadOffset = lo;
		fileSize = fs;
		memSize = ms;
		int strOff = b.getInt(shoff + shstrndx * shentsize + 16);
		for (int i = 0; i < shnum; i++) {
			int s = shoff + i * shentsize;
			String nm = cstrAt(strOff + b.getInt(s));
			sections.add(new Section(nm, b.getInt(s + 12), b.getInt(s + 20), b.getInt(s + 16), b.getInt(s + 4)));
		}
	}

	public static Elf load(Path p) throws IOException {
		return new Elf(ByteBuffer.wrap(Files.readAllBytes(p)));
	}

	private String cstrAt(int off) {
		int e = off;
		while (file.get(e) != 0) {
			e++;
		}
		byte[] t = new byte[e - off];
		file.get(off, t);
		return new String(t, StandardCharsets.ISO_8859_1);
	}

	public int entry() {
		return entry;
	}

	public List<Section> sections() {
		return sections;
	}

	public Section section(String name) {
		return sections.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
	}

	/** 파일에 실제 내용이 있는(=.bss 가 아닌) 가상 주소인가. */
	public boolean mapped(int va) {
		return Integer.compareUnsigned(va - loadAddr, fileSize) < 0;
	}

	/** 메모리 이미지(.bss 포함) 범위 안인가. */
	public boolean inImage(int va) {
		return Integer.compareUnsigned(va - loadAddr, memSize) < 0;
	}

	public int u32(int va) {
		return file.getInt(va - loadAddr + loadOffset);
	}

	public int u8(int va) {
		return file.get(va - loadAddr + loadOffset) & 0xFF;
	}

	public float f32(int va) {
		return Float.intBitsToFloat(u32(va));
	}

	/** va 에서 시작하는 인쇄 가능한 C 문자열 (최소 길이 미만이거나 비인쇄 문자가 있으면 null). */
	public String cstring(int va, int minLen) {
		if (!mapped(va)) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int a = va; mapped(a); a++) {
			int c = u8(a);
			if (c == 0) {
				return sb.length() >= minLen ? sb.toString() : null;
			}
			if ((c < 0x20 || c > 0x7E) && c != '\n' && c != '\t' && c != '\r') {
				return null;
			}
			sb.append((char) c);
			if (sb.length() > 512) {
				return null;
			}
		}
		return null;
	}

	public int loadAddr() {
		return loadAddr;
	}

	public int memEnd() {
		return loadAddr + memSize;
	}

	public int fileEnd() {
		return loadAddr + fileSize;
	}
}
