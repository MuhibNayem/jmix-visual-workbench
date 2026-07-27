# Jmix Studio Clone

An **open-source visual development studio** for Jmix applications, built as an IntelliJ IDEA plugin.
Mimics the paid Jmix Studio features with a fully functional code generation engine.

## Features

### Entity Designer
- Visual entity creation with full JPA/Jmix annotation support
- All ID strategies: UUID, Long, Integer, String, Embedded/Composite
- Generation strategies: Jmix Generated, Identity, Sequence, Assigned
- Entity types: Entity, MappedSuperclass, Embeddable, DTO, Enum
- Inheritance: Single Table, Joined, Table Per Class
- Traits: StandardEntity, Soft Delete, Multitenancy, Auditable, Version
- Associations: ManyToOne, OneToMany, ManyToMany, OneToOne with cascade/fetch config
- Compositions with @OnDelete policy
- Bean Validation: NotNull, Size, Min, Max, Pattern, Email, Digits, etc.
- Indexes, unique constraints, lifecycle callbacks
- Data repository generation
- Live code preview

### View Designer (WYSIWYG)
- Component palette: 60+ Jmix Flow UI components
- Drag-and-drop layout building
- Properties inspector for each component
- Data container configuration with fetch plans
- DataGrid column configuration
- Generic filter, pagination, actions
- Dialog configuration
- XML + Java controller generation

### CRUD Scaffolding Wizard
- **One-click full CRUD generation** from any entity:
  - Entity Java class with all annotations
  - Liquibase migration (create table, indexes, foreign keys)
  - List view (XML + Java controller) with DataGrid, filter, pagination
  - Detail view (XML + Java controller) with form, save/close actions
  - Menu entry
  - Security role with full entity/screen/menu policies
  - Localization messages
  - Fetch plans (_base, _full)
  - Optional data repository
- Configurable: list style, detail mode, database type, filters, actions

### Menu Designer
- Visual menu tree editor
- Nested menu groups and items
- Icon, shortcut, view binding

### Role Designer
- Resource roles with entity CRUD policies
- Row-level roles with JPQL/predicate/script policies
- Menu and screen policies
- Specific permissions

### Migration Generator
- Full Liquibase XML changelog generation
- Auto-generates from entity model (create table, columns, FKs, indexes)
- Manual changeset builder: createTable, addColumn, dropColumn, addForeignKey, createIndex, insertData, rawSql

### BPM Generator
- BPMN 2.0 process definition generation
- Approval process templates
- User tasks, service tasks, gateways, events

## Architecture

```
jmix-studio-clone/
├── plugin/                          # IntelliJ IDEA Plugin (Kotlin)
│   ├── build.gradle.kts             # Gradle build with IntelliJ Platform SDK
│   └── src/main/kotlin/com/jmixstudio/
│       ├── model/                   # Comprehensive data models
│       │   ├── EntityModel.kt       # Entity, attributes, associations, traits, validations
│       │   ├── ViewModel.kt         # Views, components, data containers, facets, actions
│       │   ├── MigrationModel.kt    # Full Liquibase changeset model (25+ change types)
│       │   ├── RoleModel.kt         # Resource + row-level roles
│       │   └── ProjectConfig.kt     # Jmix project detection & config
│       ├── generator/               # Code generation engine
│       │   ├── JavaClassBuilder.kt  # Generic fluent Java source builder
│       │   ├── XmlBuilder.kt        # Generic fluent XML document builder
│       │   ├── EntityGenerator.kt   # JPA entity, enum, DTO generation
│       │   ├── ViewXmlGenerator.kt  # Jmix Flow UI view XML descriptors
│       │   ├── ViewControllerGenerator.kt  # Java view controllers
│       │   ├── MigrationGenerator.kt       # Liquibase XML + entity→migration
│       │   ├── MenuGenerator.kt     # Menu XML configuration
│       │   ├── RoleGenerator.kt     # Security role interfaces
│       │   ├── DataRepositoryGenerator.kt  # Spring Data repositories
│       │   ├── EventListenerGenerator.kt   # Entity event listeners
│       │   ├── BpmGenerator.kt      # BPMN 2.0 process definitions
│       │   └── CrudOrchestrator.kt  # Full CRUD stack orchestration
│       ├── services/                # IntelliJ project services
│       │   ├── JmixProjectService.kt       # Jmix project detection
│       │   └── CodeGenerationService.kt    # File writing + VFS refresh
│       ├── bridge/
│       │   └── JcefBridge.kt        # JCEF ↔ Java JSON bridge
│       ├── toolwindow/
│       │   └── JmixStudioToolWindowFactory.kt  # JCEF tool window
│       └── actions/
│           └── Actions.kt           # IDE actions (New Entity, View, CRUD)
│
└── webui/                           # React Visual Designer UI
    ├── package.json
    ├── vite.config.ts
    └── src/
        ├── App.tsx                  # Main app with tab navigation
        ├── bridge/index.ts          # JCEF bridge communication layer
        ├── store/index.ts           # Zustand state management
        ├── types/index.ts           # TypeScript types (mirrors Kotlin models)
        └── components/
            ├── EntityDesigner/      # Visual entity modeling
            ├── ViewDesigner/        # WYSIWYG view designer
            ├── CrudWizard/          # One-click CRUD scaffolding
            ├── MenuDesigner/        # Menu tree editor
            ├── RoleDesigner/        # Security role designer
            ├── MigrationPanel/      # Liquibase migration builder
            └── shared/              # Toast, common components
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Plugin | Kotlin, IntelliJ Platform SDK 2024.1, JCEF |
| Code Generation | Custom template engine (JavaClassBuilder, XmlBuilder) |
| UI | React 18, TypeScript, Tailwind CSS, Zustand |
| Build | Gradle (plugin), Vite (UI) |
| Serialization | Gson (Java ↔ JS JSON bridge) |

## Building

### Prerequisites
- JDK 17+
- Node.js 18+
- IntelliJ IDEA (for running the plugin)

### Build the UI
```bash
cd webui
npm install
npm run build
```

### Build the Plugin
```bash
cd plugin
./gradlew buildPlugin
```

The plugin ZIP will be in `plugin/build/distributions/`.

### Development Mode
```bash
# Terminal 1: Start Vite dev server
cd webui && npm run dev

# Terminal 2: Run plugin with dev UI
cd plugin && ./gradlew runIde -Djmixstudio.dev.url=http://localhost:5173
```

### Install in IntelliJ IDEA
1. Build the plugin: `cd plugin && ./gradlew buildPlugin`
2. Open IntelliJ IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk
3. Select `plugin/build/distributions/jmix-studio-clone-1.0.0.zip`
4. Restart IDE
5. Open a Jmix project → right panel "Jmix Studio" tool window

## How It Works

1. **JCEF Bridge**: The plugin embeds a Chromium browser (JCEF) in a tool window, loading the React UI.
2. **JSON Protocol**: The React UI sends JSON commands via `window.javaBridge.send(action, payload)`.
3. **Code Generation**: The Java backend deserializes the payload into model objects, runs the appropriate generator, and writes files to the project.
4. **VFS Refresh**: After writing files, the plugin refreshes IntelliJ's Virtual File System so the IDE picks up new files immediately.

## Code Generation Engine

The engine is built on two generic builders:

- **JavaClassBuilder**: Fluent API for constructing Java source files with automatic import management, annotations, fields, methods, inner classes, and enum constants.
- **XmlBuilder**: Fluent API for constructing XML documents with namespaces, attributes, nested elements, and text content.

Generators are stateless objects that transform model objects into source code strings. The CrudOrchestrator composes multiple generators to produce a complete CRUD stack from a single entity definition.

## License

Open Source — Free to use, modify, and distribute.
