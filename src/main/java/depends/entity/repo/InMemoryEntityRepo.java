package depends.entity.repo;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import depends.entity.*;
import multilang.depends.util.file.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;


public class InMemoryEntityRepo extends SimpleIdGenerator implements EntityRepo {

    public class EntityＭapIterator implements Iterator<Entity> {

        private Iterator<Entry<Integer, Entity>> entryIterator;

        public EntityＭapIterator(Set<Entry<Integer, Entity>> entries) {
            this.entryIterator = entries.iterator();
        }

        @Override
        public boolean hasNext() {
            return entryIterator.hasNext();
        }

        @Override
        public Entity next() {
            return entryIterator.next().getValue();
        }

    }

    private Map<String, Entity> allEntieisByName;
    private Map<Integer, Entity> allEntitiesById;
    private Map<String, List<Entity>> allImplementEntities;
    private Map<String, MethodDeclaration> allMethods;
    private List<Entity> allFileEntitiesByOrder;

    public InMemoryEntityRepo() {
        allEntieisByName = new TreeMap<>();
        allEntitiesById = new TreeMap<>();
        allImplementEntities = new TreeMap<>();
        allMethods = new TreeMap<>();
        allFileEntitiesByOrder = new LinkedList<>();
        add(TypeEntity.buildInType);
    }

    @Override
    public void addImplementEntities(Entity entity) {

        if (entity instanceof TypeEntity) {
            TypeEntity typeEntity = (TypeEntity) entity;
            // 遍历实现了多个接口的情况
            typeEntity.getImplementedTypes().forEach(interfaceEntity -> {
                String infQualifiedName = interfaceEntity.getQualifiedName();
                List<Entity> entities = allImplementEntities.computeIfAbsent(infQualifiedName, k -> new ArrayList<>());
                // 只有当 entities 列表中不包含 typeEntity 时，才添加进去
                synchronized (entities) {
                    if (!entities.contains(typeEntity)) {
                        entities.add(typeEntity);
                    }
                }
            });
        }
        if (entity instanceof FunctionEntity) {
            FunctionEntity funcEntity = (FunctionEntity) entity;
            String funcName = funcEntity.getRawName().getName();//getExpressInfoByExpressCode
            String params = funcEntity.getParameters().stream()
                    .map(varEntity -> varEntity.getRawName().getName())
                    .collect(Collectors.joining(","));// expressCode,expressId

            String pathName = funcEntity.getParent().getRawName().getName()+","
                    +funcName + "," + params;//ExpressInnerServiceImpl,getExpressInfoByExpressCode,expressCode,expressId
            // 包含同名方法
            List<Entity> entities = allImplementEntities.computeIfAbsent(pathName, k -> new ArrayList<>());
            synchronized (entities) {
                if (!entities.contains(funcEntity)) {
                    entities.add(funcEntity);
                }
            }
        }
    }

    @Override
    public void addMethodDeclaration(Entity entity){
        if (entity instanceof FileEntity) {
            try {
                CompilationUnit uc = StaticJavaParser.parse(Files.newInputStream(Paths.get(entity.getQualifiedName())));
                NodeList<TypeDeclaration<?>> types = uc.getTypes();
                for (TypeDeclaration<?> typeDeclaration : types) {
                    // 获取类或者接口声明
                    if (typeDeclaration instanceof ClassOrInterfaceDeclaration){
                        ClassOrInterfaceDeclaration classOrInterfaceDeclaration = (ClassOrInterfaceDeclaration) typeDeclaration;
                        String classOrInterfaceName = classOrInterfaceDeclaration.getNameAsString();//ExpressServiceImpl
                        for (BodyDeclaration<?> bodyDeclaration : typeDeclaration.getMembers()) {
                            // 获取方法声明
                            if (bodyDeclaration instanceof MethodDeclaration){
                                MethodDeclaration methodDeclaration = (MethodDeclaration)bodyDeclaration;
                                String funcName = methodDeclaration.getName().asString();
                                String params = methodDeclaration.getParameters().stream()
                                        .map(Parameter::getName).map(SimpleName::asString)
                                        .collect(Collectors.joining(","));
                                String pathName = classOrInterfaceName+","+funcName + "," + params;
                                allMethods.put(pathName, methodDeclaration);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public MethodDeclaration getMethodDeclaration(String pathName) {
        return allMethods.get(pathName);
    }

	@Override
	public List<Entity> getImplementEntities (String qualifiedName) {
		return allImplementEntities.get(qualifiedName);
	}

    @Override
    public Entity getEntity(String entityName) {
        return allEntieisByName.get(entityName);
    }

    @Override
    public Entity getEntity(Integer entityId) {
        return allEntitiesById.get(entityId);
    }

    @Override
    public void add(Entity entity) {
        allEntitiesById.put(entity.getId(), entity);
        String name = entity.getRawName().uniqName();
        if (entity.getQualifiedName() != null && !(entity.getQualifiedName().isEmpty())) {
            name = entity.getQualifiedName();
        }
        if (allEntieisByName.containsKey(name)) {
            Entity existedEntity = allEntieisByName.get(name);
            if (existedEntity instanceof MultiDeclareEntities) {
                ((MultiDeclareEntities) existedEntity).add(entity);
            } else {
                MultiDeclareEntities eMultiDeclare = new MultiDeclareEntities(existedEntity, this.generateId());
                eMultiDeclare.add(entity);
                allEntieisByName.put(name, eMultiDeclare);
            }
        } else {
            allEntieisByName.put(name, entity);
        }
        if (entity.getParent() != null)
            Entity.setParent(entity, entity.getParent());
    }

    @Override
    public Iterator<Entity> entityIterator() {
        return new EntityＭapIterator(allEntitiesById.entrySet());
    }


    @Override
    public void update(Entity entity) {
    }

    @Override
    public Entity getEntity(GenericName rawName) {
        return this.getEntity(rawName.uniqName());
    }

    @Override
    public Collection<Entity> getFileEntities() {
        return allFileEntitiesByOrder;
    }

    @Override
    public Iterator<Entity> sortedFileIterator() {
        return allFileEntitiesByOrder.iterator();
    }

    @Override
    public void clear() {
        allEntieisByName.clear();
        allImplementEntities.clear();
        allEntitiesById.clear();
        allFileEntitiesByOrder.clear();
    }

    @Override
    public FileEntity getFileEntity(String fileFullPath) {
        fileFullPath = FileUtil.uniqFilePath(fileFullPath);
        Entity entity = this.getEntity(fileFullPath);
        if (entity == null) return null;
        if (entity instanceof FileEntity) return (FileEntity) entity;
        if (entity instanceof MultiDeclareEntities) {
            MultiDeclareEntities multiDeclare = (MultiDeclareEntities) entity;
            for (Entity theEntity : multiDeclare.getEntities()) {
                if (theEntity instanceof FileEntity) {
                    return (FileEntity) theEntity;
                }
            }
        }
        return null;
    }

    @Override
    public void completeFile(String fileFullPath) {
        FileEntity fileEntity = getFileEntity(fileFullPath);
        // in case of parse error(throw exception), the file entity may not exists
        if (fileEntity != null) {
            fileEntity.cacheAllExpressions();
            allFileEntitiesByOrder.add(fileEntity);
        }
    }
}
