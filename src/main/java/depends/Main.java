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
import org.apache.commons.lang3.tuple.MutablePair;
import picocli.CommandLine;
import picocli.CommandLine.PicocliException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The entry pooint of depends
 */
public class Main {

    public static MutablePair<String, String> currentInterface = new MutablePair<>("", "");

    public static void main(String[] args) {
        try {
            args = new String[]{"java","/Users/esvc/biyao/code/express.biyao.com", "./output/express.biyao.com.061901"};
            new LangRegister().register();
            ParseCommand appArgs = CommandLine.populateCommand(new ParseCommand(), args);

            if (appArgs.help) {
                CommandLine.usage(new ParseCommand(), System.out);
                System.exit(0);
            }

            executeCommand(appArgs);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void handleException(Exception e) {
        if (e instanceof PicocliException) {
            CommandLine.usage(new ParseCommand(), System.out);
        } else if (e instanceof ParameterException) {
            System.err.println(e.getMessage());
        } else {
            e.printStackTrace();
        }
        System.exit(0);
    }

    private static void executeCommand(ParseCommand args) throws IOException {
        String lang = args.getLang();
        String inputDir = FileUtil.uniqFilePath(args.getSrc());
        String outputDir = args.getOutputDir();
        String keyword = args.getKeyword();

        createOutputDirectory(outputDir);

        AbstractLangProcessor langProcessor = LangProcessorRegistration.getRegistry().getProcessorOf(lang);
        if (langProcessor == null) {
            System.err.println("Not support this language: " + lang);
            return;
        }

        IBindingResolver bindingResolver = new BindingResolver(langProcessor, false, true);
        long startTime = System.currentTimeMillis();

        EntityRepo entityRepo = langProcessor.buildDependencies(inputDir, new String[]{}, bindingResolver);
        new RelationCounter(entityRepo, langProcessor, bindingResolver).computeRelations();
        System.out.println("Dependency done....");

        Map<String, List<Entity>> groupedEntities = groupEntitiesByType(entityRepo);
        processEntity(groupedEntities, entityRepo, outputDir, keyword);

        long endTime = System.currentTimeMillis();
        TemporaryFile.getInstance().delete();
        CacheManager.create().shutdown();
        System.out.println("outputDir: " + outputDir);
        System.out.println("Consumed time: " + (float) ((endTime - startTime) / 1000.00) + " s,  or "
                + (float) ((endTime - startTime) / 60000.00) + " min.");
    }

    private static void createOutputDirectory(String outputDir) {
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("目标目录 " + outputDir + " 创建失败");
        }
    }

    private static Map<String, List<Entity>> groupEntitiesByType(EntityRepo entityRepo) {
        Map<String, List<Entity>> groupedEntities = new HashMap<>();
        entityRepo.entityIterator().forEachRemaining(entity -> {
            String key = entity.getClass().getSimpleName();
            groupedEntities.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        });
        return groupedEntities;
    }

    private static void processEntity(Map<String, List<Entity>> groupedEntities, EntityRepo entityRepo, String outputDir, String keyword) throws IOException {
        List<Entity> fileEntityList = groupedEntities.get("FileEntity");
        if (fileEntityList == null || fileEntityList.isEmpty()) {
            throw new RuntimeException("无可解析的文件，请确认项目路径是否准确");
        }

        List<FileEntity> fileEntities = fileEntityList.stream()
                .map(FileEntity.class::cast)
                .collect(Collectors.toList());

        String searchKeyword = (keyword == null || keyword.isEmpty() || "null".equals(keyword)) ? "dfhksdhjfshksjhdfkasjd" : keyword;
        List<String> entryFiles = fileEntities.stream()
                .map(FileEntity::getQualifiedName)
                .filter(filePath -> hasEntryAnnotation(filePath, searchKeyword))
                .collect(Collectors.toList());

        if (!entryFiles.isEmpty()) {
            System.out.println("发现文件解析标识“" + searchKeyword + "”，本次仅处理以下文件:");
            entryFiles.forEach(System.out::println);
        } else {
            System.out.println("未发现文件解析标识，所有文件全部处理");
        }

        for (FileEntity entity : fileEntities) {
            if (!entryFiles.isEmpty() && !entryFiles.contains(entity.getQualifiedName())) {
                continue;
            }
            processFileEntity(entity, entityRepo, outputDir);
        }
    }

    private static void processFileEntity(FileEntity entity, EntityRepo entityRepo, String outputDir) throws IOException {
        for (Entity child : entity.getChildren()) {
            if (child instanceof TypeEntity) {
                for (Entity funcChild : child.getChildren()) {
                    if (funcChild instanceof FunctionEntity) {
                        processFunctionEntity((FunctionEntity) funcChild, entityRepo, outputDir, child);
                    }
                }
            }
        }
    }

    private static void processFunctionEntity(FunctionEntity functionEntity, EntityRepo entityRepo, String outputDir, Entity child) throws IOException {
        Set<Integer> ids = new HashSet<>();
        ids.add(functionEntity.getId());

        String funcName = functionEntity.getRawName().getName();
        String params = functionEntity.getParameters().stream()
                .map(varEntity -> varEntity.getRawName().getName())
                .collect(Collectors.joining(","));

        String pathName = child.getRawName().getName() + "," + funcName + "," + params;
        String logFileName = outputDir + "/" + child.getRawName().getName() + "-" + funcName;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName + ".log"))) {
            MethodDeclaration methodDeclaration = entityRepo.getMethodDeclaration(pathName);
            if (methodDeclaration != null) {
                writeMethodSignature(writer, child, methodDeclaration);
                writer.write(cleanCommentText(methodDeclaration.getComment().orElse(null) + "") + "\n");
                writer.write(methodDeclaration.getTokenRange().orElse(null) + "\n");
                processRelations(functionEntity, entityRepo, logFileName, ids);
            }
        }
        deleteEmptyFiles(outputDir);
    }

    private static void writeMethodSignature(BufferedWriter writer, Entity child, MethodDeclaration methodDeclaration) throws IOException {
        Collection<TypeEntity> implementedTypes = ((TypeEntity) child).getImplementedTypes();
        if (implementedTypes != null && !implementedTypes.isEmpty()) {
            for (TypeEntity typeEntity : implementedTypes) {
                for (Entity signNameEntity : typeEntity.getFunctions()) {
                    if (methodDeclaration.getName().asString().equals(signNameEntity.getRawName().getName())) {
                        writer.write("接口签名：" + signNameEntity.getQualifiedName() + "\n");
                        currentInterface.setLeft(signNameEntity.getQualifiedName());
                        currentInterface.setRight(methodDeclaration.getComment().orElse(null) + "");
                    }
                }
            }
        }
    }

    private static boolean hasEntryAnnotation(String filePath, String keyword) {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/**") || line.contains("*")) {
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

    public static void deleteEmptyFiles(String directoryPath) {
        try {
            Files.walk(Paths.get(directoryPath))
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            if (Files.size(path) == 0) {
                                Files.delete(path);
                                System.out.println("Deleted empty file: " + path);
                            }
                        } catch (IOException e) {
                            System.err.println("Error while deleting file: " + path);
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error while walking the directory: " + directoryPath);
            e.printStackTrace();
        }
    }

    private static void processRelations(Entity entity, EntityRepo entityRepo, String logFileName, Set<Integer> ids) throws IOException {
        for (Relation relation : entity.getRelations()) {
            if (relation.getEntity().getId().equals(entity.getId())) {
                continue;
            }
            if ("Call".equals(relation.getType()) && !ids.contains(relation.getEntity().getId())) {
                if (logFileName.endsWith(".log")) {
                    logFileName = logFileName.substring(0, logFileName.lastIndexOf("."));
                }
                String fileName = logFileName + "-" + relation.getEntity().getRawName().getName();
                printCodeBody(relation.getEntity(), entityRepo, fileName, ids);
                processRelations(relation.getEntity(), entityRepo, fileName, ids);
            }
        }
    }

    private static void printCodeBody(Entity entity, EntityRepo entityRepo, String logFileName, Set<Integer> ids) throws IOException {
        if (entity instanceof TypeEntity) {
            handleTypeEntity((TypeEntity) entity, entityRepo, logFileName, ids);
        } else if (entity instanceof FunctionEntity) {
            handleFunctionEntity((FunctionEntity) entity, entityRepo, logFileName, ids);
        }
    }

    private static void handleTypeEntity(TypeEntity entity, EntityRepo entityRepo, String logFileName, Set<Integer> ids) throws IOException {
        Entity parent = entity.getParent();
        if (parent instanceof FileEntity) {
            ids.add(entity.getId());
            for (Entity funcEntity : entity.getFunctions()) {
                ids.add(funcEntity.getId());
                processRelations(funcEntity, entityRepo, logFileName, ids);
            }
        }
    }

    private static void handleFunctionEntity(FunctionEntity funcEntity, EntityRepo entityRepo, String logFileName, Set<Integer> ids) throws IOException {
        String funcName = funcEntity.getRawName().getName();
        String params = funcEntity.getParameters().stream()
                .map(varEntity -> varEntity.getRawName().getName())
                .collect(Collectors.joining(","));

        List<Entity> implementEntities = entityRepo.getImplementEntities(funcEntity.getParent().getQualifiedName());
        if (implementEntities != null && !implementEntities.isEmpty()) {
            handleImplementedFunctions(implementEntities, funcName, params, entityRepo, logFileName, ids);
        } else {
            handleDirectFunction(funcEntity, entityRepo, logFileName, funcName, params, ids);
        }
    }

    private static void handleImplementedFunctions(List<Entity> implementEntities, String funcName, String params, EntityRepo entityRepo, String logFileName, Set<Integer> ids) throws IOException {
        ids.add(funcName.hashCode());
        for (Entity implementEntity : implementEntities) {
            String pathName = implementEntity.getRawName().getName() + "," + funcName + "," + params;
            MethodDeclaration methodDeclaration = entityRepo.getMethodDeclaration(pathName);
            if (methodDeclaration != null) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName + "-" + funcName + ".log"))) {
                    writeReference(writer);
                    writer.write(cleanCommentText(methodDeclaration.getComment().orElse(null) + "") + "\n");
                    writer.write(methodDeclaration.getTokenRange().orElse(null) + "\n");
                }
            }
            List<Entity> funcImplementCall = entityRepo.getImplementEntities(pathName);
            if (funcImplementCall != null) {
                for (Entity call : funcImplementCall) {
                    ids.add(call.getId());
                    processRelations(call, entityRepo, logFileName, ids);
                }
            }
        }
    }

    private static void handleDirectFunction(FunctionEntity funcEntity, EntityRepo entityRepo, String logFileName, String funcName, String params, Set<Integer> ids) throws IOException {
        String pathName = funcEntity.getParent().getRawName().getName() + "," + funcName + "," + params;
        ids.add(funcEntity.getId());
        MethodDeclaration methodDeclaration = entityRepo.getMethodDeclaration(pathName);
        if (methodDeclaration != null) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName + "-" + funcName + ".log"))) {
                writeReference(writer);
                writer.write(cleanCommentText(methodDeclaration.getComment().orElse(null) + "") + "\n");
                writer.write(methodDeclaration.getTokenRange().orElse(null) + "" + "\n");
            }
        }
    }

    public static void writeReference(BufferedWriter writer) throws IOException {
        writer.write("该方法被接口[" + currentInterface.getLeft() + "]调用。\n");
        if (currentInterface.getRight() != null && !currentInterface.getRight().isEmpty()
                && !"null".equals(currentInterface.getRight())) {
            writer.write("该接口功能说明如下:[" + currentInterface.getRight() + "]\n");
        }
    }

//    private static String cleanText(String text) {
//        return text == null ? "" : text;

//        text = text.replace("\n", " ").replace("\r", " ");
//        return text.replaceAll("\\s+", " ");

//        text = text.replaceAll("(\\r\\n|\\r)", "\n");
//        return text.replaceAll("[ \\t\\f\\v]+", " ");
//    }

    private static final Pattern AUTHOR_PATTERN = Pattern.compile("@author.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("@date.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODIFIED_PATTERN = Pattern.compile("@modified.*", Pattern.CASE_INSENSITIVE);

    private static String cleanCommentText(String text) {
        if (text == null || "null".equals(text) || text.isEmpty()) {
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


}
