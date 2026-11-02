# KLEE Kernel Manager

Gerenciador Android especializado para diagnóstico do POCO X8 Pro (codinome `klee`, plataforma MT6899).

## Estado atual

Versão inicial de diagnóstico **somente leitura**. O aplicativo consulta identidade do aparelho, políticas CPUFreq e informações básicas do devfreq Mali por meio de shell root (libsu). Nenhum parâmetro de frequência, tensão, governor ou controle térmico é alterado.

## Compilar

O GitHub Actions compila o APK de debug em cada push para `main` e também sob demanda. O APK fica disponível como artefato na execução do workflow.

Localmente, abra o projeto no Android Studio (JDK 17, Android SDK 35) e execute `assembleDebug`.

## Segurança

Não habilite escritas em nós sysfs/procfs sem identificar formato, unidade, limites, permissões e comportamento de read-back/rollback no aparelho real. Os caminhos detectados em relatórios são apenas pistas e devem ser validados em cada firmware/kernel.

## Stack

- Kotlin + Jetpack Compose
- MVVM
- libsu para execução root
- Android Gradle Plugin 8.7.3 / Kotlin 2.0.21
