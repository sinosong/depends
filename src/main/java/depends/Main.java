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

import com.github.javaparser.ast.body.MethodDeclaration;
import depends.entity.Entity;
import depends.entity.FileEntity;
import depends.entity.FunctionEntity;
import depends.entity.TypeEntity;
import depends.entity.repo.EntityRepo;
import depends.extractor.AbstractLangProcessor;
import depends.extractor.LangProcessorRegistration;
import depends.relations.BindingResolver;
import depends.relations.IBindingResolver;
import depends.relations.Relation;
import depends.relations.RelationCounter;
import multilang.depends.util.file.FileUtil;
import multilang.depends.util.file.TemporaryFile;
import net.sf.ehcache.CacheManager;
import picocli.CommandLine;
import picocli.CommandLine.PicocliException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The entry pooint of depends
 */
public class Main {

    public static void main(String[] args) {
        try {
              args = new String[]{"java","/Users/esvc/biyao/code/express.biyao.com", "./output/express.biyao.com.part"};
            LangRegister langRegister = new LangRegister();
            langRegister.register();
            ParseCommand appArgs = CommandLine.populateCommand(new ParseCommand(), args);
            if (appArgs.help) {
                CommandLine.usage(new ParseCommand(), System.out);
                System.exit(0);
            }
            executeCommand(appArgs);
        } catch (Exception e) {
            if (e instanceof PicocliException) {
                CommandLine.usage(new ParseCommand(), System.out);
            } else if (e instanceof ParameterException) {
                System.err.println(e.getMessage());
            } else {
                e.printStackTrace();
            }
            System.exit(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void executeCommand(ParseCommand args) throws IOException {
        String lang = args.getLang();
        String inputDir = args.getSrc();
        String[] includeDir = new String[] {};
        String outputDir = args.getOutputDir();
        inputDir = FileUtil.uniqFilePath(inputDir);
        String keyword = args.getKeyword();

        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()){
            System.err.println("目标目录 " + outputDir + " 创建失败");
        }

        AbstractLangProcessor langProcessor = LangProcessorRegistration.getRegistry().getProcessorOf(lang);
        if (langProcessor == null) {
            System.err.println("Not support this language: " + lang);
            return;
        }

        IBindingResolver bindingResolver = new BindingResolver(langProcessor, false, true);

        long startTime = System.currentTimeMillis();
        //step1: build data
        EntityRepo entityRepo = langProcessor.buildDependencies(inputDir, includeDir, bindingResolver);

        new RelationCounter(entityRepo, langProcessor, bindingResolver).computeRelations();
        System.out.println("Dependency done....");

        Map<String, List<Entity>> groupedEntities = new HashMap<>();
        Iterator<Entity> it = entityRepo.entityIterator();
        while (it.hasNext()) {
            Entity entity = it.next();
            String key = entity.getClass().getSimpleName();
            groupedEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }

        processEntity(groupedEntities, entityRepo, outputDir, keyword);

        long endTime = System.currentTimeMillis();
        TemporaryFile.getInstance().delete();
        CacheManager.create().shutdown();
        System.out.println("outputDir: "+outputDir);
        System.out.println("Consumed time: " + (float) ((endTime - startTime) / 1000.00) + " s,  or "
                + (float) ((endTime - startTime) / 60000.00) + " min.");
    }

    private static void processEntity(Map<String, List<Entity>> groupedEntities, EntityRepo entityRepo, String outputDir, String keyword) throws IOException {
        //以文件为入口
        List<Entity> fileEntityList = groupedEntities.get("FileEntity");
        if(fileEntityList == null || fileEntityList.isEmpty()){
            throw new RuntimeException("无可解析的文件，请确认项目路径是否准确");
        }
        List<FileEntity> fileEntity = fileEntityList.stream()
                .map(FileEntity.class::cast)
                .collect(Collectors.toList());

        // 设置默认关键字
        String searchKeyword = (keyword == null || keyword.isEmpty() || "null".equals(keyword)) ? "dfhksdhjfshksjhdfkasjd" : keyword;
        // 获取带有注释标识的文件列表
        List<String> entryFiles = fileEntity.stream()
                .map(FileEntity::getQualifiedName)
                .filter(filePath -> hasEntryAnnotation(filePath, searchKeyword))
                .collect(Collectors.toList());

        if (!entryFiles.isEmpty()) {
            System.out.println("发现文件解析标识“"+searchKeyword+"”，本次仅处理以下文件:");
            entryFiles.forEach(System.out::println);
        } else {
            System.out.println("未发现文件解析标识，所有文件全部处理");
        }

        for (FileEntity entity : fileEntity) {
            if (!entryFiles.isEmpty() && !entryFiles.contains(entity.getQualifiedName())) {
                continue;
            }
            Collection<Entity> children = entity.getChildren();
            if (children != null && !children.isEmpty()) {
                for (Entity child : children) {
                    if (child instanceof TypeEntity) {
                        for (Entity funcChild : child.getChildren()) {
                            if (funcChild instanceof FunctionEntity) {

                                Set<Integer> ids = new HashSet<>();
                                ids.add(funcChild.getId());
                                FunctionEntity functionEntity = (FunctionEntity) funcChild;
                                String funcName = functionEntity.getRawName().getName();//getExpressInfoByExpressCode
                                String params = functionEntity.getParameters().stream()
                                        .map(varEntity -> varEntity.getRawName().getName())
                                        .collect(Collectors.joining(","));
                                //ExpressInnerServiceImpl,getExpressInfoByExpressCode,expressCode,expressId
                                String pathName = child.getRawName().getName() + "," + funcName + "," + params;
                                String logFileName = outputDir + "/" + child.getRawName().getName() + "-" + funcName + ".log";
                                try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName))) {
                                    MethodDeclaration methodDeclaration = entityRepo.getMethodDeclaration(pathName);
                                    if (null != methodDeclaration) {
                                        //写入接口方法签名,source:com.biyao.express.dubbo.client.express.IExpressService.getExpressInfoByExpressCode
                                        Collection<TypeEntity> implementedTypes = ((TypeEntity) child).getImplementedTypes();
                                        if (implementedTypes != null && !implementedTypes.isEmpty()) {
                                            implementedTypes.forEach(typeEntity -> typeEntity.getFunctions().forEach(signNameEntity -> {
                                                if (methodDeclaration.getName().asString().equals(signNameEntity.getRawName().getName())) {
                                                    try {
                                                        writer.write("接口签名：" + signNameEntity.getQualifiedName() + "\n");
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                }
                                            }));
                                        }
                                        if (methodDeclaration.getComment().isPresent()) {
                                            writer.write(cleanCommentText(methodDeclaration.getComment().orElse(null) + "") + "\n");
                                        }
                                        writer.write(cleanText(methodDeclaration.getTokenRange().orElse(null) + "") + "\n");
                                        processRelations(funcChild, entityRepo, writer, ids);
                                    }
                                }
                                try {
                                    Path path = Paths.get(logFileName);
                                    long fileSizeInBytes = Files.size(path);
                                    if (fileSizeInBytes == 0) {
                                        Files.delete(path);
                                    }
                                    if(fileSizeInBytes > MAX_FILE_SIZE){
                                        compressContent(path);
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean hasEntryAnnotation(String filePath, String keyword) {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/**") || line.contains("*")) { // 开始读取注释
                    while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                        if (line.contains(keyword)) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading file: " + filePath);
        }
        return false;
    }

    /**
     * 处理依赖方法
     */
    private static void processRelations(Entity entity, EntityRepo entityRepo, BufferedWriter writer, Set<Integer> ids) throws IOException {
        ArrayList<Relation> relations = entity.getRelations();
        if (relations != null && !relations.isEmpty()) {
            for (Relation relation : relations) {
                if (relation.getEntity().getId().equals(entity.getId())) {
                    continue;
                }
                //Call为方法调用
                if ("Call".equals(relation.getType()) && !ids.contains(relation.getEntity().getId())) {
                    printCodeBody(relation.getEntity(), entityRepo, writer, ids);
                    processRelations(relation.getEntity(), entityRepo, writer, ids);
                }
            }
        }
    }

    private static void printCodeBody(Entity entity, EntityRepo entityRepo, BufferedWriter writer, Set<Integer> ids) throws IOException {
        if (ids.contains(entity.getId())) {
            return;
        }
        //对象依赖，将对象代码完整引入，如new ByException、new RegisterSFRouteHandler
        if (entity instanceof TypeEntity) {
            Entity parent = entity.getParent();
            if (parent instanceof FileEntity) {
                ids.add(entity.getId());
                ((TypeEntity) entity).getFunctions().forEach(funcEntity -> {
                    try {
                        ids.add(funcEntity.getId());
                        processRelations(funcEntity, entityRepo, writer, ids);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } else if (entity instanceof FunctionEntity) {
            FunctionEntity funcEntity = (FunctionEntity) entity;
            String funcName = funcEntity.getRawName().getName();//getExpressInfoByExpressCode
            String params = funcEntity.getParameters().stream()
                    .map(varEntity -> varEntity.getRawName().getName())
                    .collect(Collectors.joining(","));// expressCode,expressId

            // 根据方法FunctionEntity 找到所属类TypeEntity，获取类名称
            //处理interface调用
            List<Entity> implementEntities = entityRepo.getImplementEntities(entity.getParent().getQualifiedName());
            // 多个实现类
            if (null != implementEntities && !implementEntities.isEmpty()) {
                ids.add(entity.getId());
                for (Entity implementEntity : implementEntities) {
                    String pathName = implementEntity.getRawName().getName() + ","
                            + funcName + "," + params;
                    //取实现类代码
                    MethodDeclaration methodDeclaration = entityRepo.getMethodDeclaration(pathName);
                    if (null != methodDeclaration) {
						if (methodDeclaration.getComment().isPresent()) {
							writer.write(cleanCommentText(methodDeclaration.getComment().orElse(null) + "")+ "\n");
						}
                        writer.write(cleanText(methodDeclaration.getTokenRange().orElse(null) + "") + "\n");
                    }
                    //取实现类方法
                    List<Entity> funcImplementCall = entityRepo.getImplementEntities(pathName);
                    if (null != funcImplementCall) {
                        for (Entity call : funcImplementCall) {
                            ids.add(call.getId());
                            processRelations(call, entityRepo, writer, ids);
                        }
                    }
                }
            } else {
                //非interface，直接读取方法体
                String pathName = entity.getParent().getRawName().getName() + "," + funcName + "," + params;
                ids.add(entity.getId());
                //取实现类代码
                MethodDeclaration methodDeclaration = entityRepo.getMethodDeclaration(pathName);
                if (methodDeclaration != null) {
					if (methodDeclaration.getComment().isPresent()) {
						writer.write(cleanCommentText(methodDeclaration.getComment().orElse(null) + "")+ "\n");
					}
                    writer.write(cleanText(methodDeclaration.getTokenRange().orElse(null) + "") + "\n");
                }
            }
        }
    }

    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        text = text.replace("\n", " ").replace("\r", " ");
        return text.replaceAll("\\s+", " ");
    }

    private static final Pattern AUTHOR_PATTERN = Pattern.compile("@author.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("@date.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODIFIED_PATTERN = Pattern.compile("@modified.*", Pattern.CASE_INSENSITIVE);

    private static String cleanCommentText(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\n");
        StringBuilder cleanedText = new StringBuilder();
        for (String line : lines) {
            if (!AUTHOR_PATTERN.matcher(line.trim()).matches()
                    && !DATE_PATTERN.matcher(line.trim()).matches()
                    && !MODIFIED_PATTERN.matcher(line.trim()).matches()) {
                cleanedText.append(line).append("\n");
            }
        }
        return cleanedText.toString().replaceAll("\\s+", "").trim();
    }

    public static int MAX_FILE_SIZE = 122880;//超过120k截断
    private static final Pattern SET_PATTERN = Pattern.compile("^\\s*public\\s+void\\s+set[A-Z][a-zA-Z0-9]*\\s*\\([^)]*\\)\\s*\\{\\s*this\\.[a-zA-Z0-9]+\\s*=\\s*[a-zA-Z0-9]+\\s*;\\s*\\}\\s*$");
    private static final Pattern GET_PATTERN = Pattern.compile("^\\s*public\\s+[a-zA-Z0-9<>,\\s]+\\s+get[A-Z][a-zA-Z0-9]*\\s*\\([^)]*\\)\\s*\\{\\s*return\\s+[a-zA-Z0-9]+\\s*;\\s*\\}\\s*$");

    private static void compressContent(Path path) throws IOException {
        // 1. 读取文件内容
        String content = new String(Files.readAllBytes(path));
        // 2. 按行拆分内容，并检查每行，清理 set 和 get 方法
        StringBuilder filteredBuilder = new StringBuilder();
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (!isSetOrGetMethod(line)) {
                filteredBuilder.append(line).append("\n");
            }
        }

        String filteredContent = filteredBuilder.toString();
        // 3. 判断是否超过128KB，超过则继续清理空字符，不超过写回新内容
        if (filteredContent.getBytes().length > MAX_FILE_SIZE) {
            String cleanedContent = filteredContent;
//            String cleanedContent = filteredContent.replaceAll("\\s+", "");
            writeContent(path, cleanedContent);
        } else {
            Files.write(path, filteredContent.getBytes());
        }
    }

    private static boolean isSetOrGetMethod(String line) {
        return SET_PATTERN.matcher(line).matches() || GET_PATTERN.matcher(line).matches();
    }

    private static void writeContent(Path path, String content) throws IOException {
        if (content.getBytes().length > MAX_FILE_SIZE) {
            System.err.println("文件大小超过 "+ MAX_FILE_SIZE/1024 +"KB, 截断内容");
            byte[] truncatedBytes = new byte[MAX_FILE_SIZE];
            System.arraycopy(content.getBytes(), 0, truncatedBytes, 0, MAX_FILE_SIZE);
            Files.write(path, truncatedBytes);
        } else {
            Files.write(path, content.getBytes());
        }
    }

}
