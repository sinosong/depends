/*
MIT License

Copyright (c) 2018-2019 Gang ZHANG

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package depends;

import depends.deptypes.DependencyType;
import depends.extractor.LangProcessorRegistration;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "Parse")
public class ParseCommand {
	public static class SupportedLangs extends ArrayList<String> {
		private static final long serialVersionUID = 1L;
		public SupportedLangs() { super( LangProcessorRegistration.getRegistry().getLangs()); }
	}

	public static class SupportedTypes extends ArrayList<String> {
		private static final long serialVersionUID = 1L;
		public SupportedTypes() { super( DependencyType.allDependencies()); }
	}

	@Parameters(index = "0", completionCandidates = ParseCommand.SupportedLangs.class, description = "项目语言，如java")
    private String lang;
	@Parameters(index = "1", description = "待分析文件夹，如：/Users/hannasong/biyao/code/express.biyao.com/express-dubbo-soa")
    private String src;
	@Parameters(index = "2",  description = "输出解析文件路径")
	private String dir;
    @Option(names = {"-k", "--keyword"}, description = "只有注释中包含关键词的文件会被解析")
	private String keyword = "";
	@Option(names = {"-h","--help"}, usageHelp = true, description = "显示帮助信息")
	boolean help;

	public ParseCommand() {
	}
	public String getLang() {
		return lang;
	}
	public void setLang(String lang) {
		this.lang = lang;
	}
	public String getSrc() {
		return src;
	}
	public void setSrc(String src) {
		this.src = src;
	}
	public String getKeyword() {
		return keyword;
	}
	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}
	public boolean isHelp() {
		return help;
	}
	public String getOutputDir() {
		if (dir==null) {
			dir = System.getProperty("user.dir");
		}
		return dir;
	}
}
