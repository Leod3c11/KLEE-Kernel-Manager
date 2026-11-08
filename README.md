# MT6899 Manager

Aplicativo Android para gerenciamento do POCO X8 Pro (codinome `klee`, plataforma MediaTek MT6899), com acesso root via KernelSU ou Magisk.

## Recursos

- Painéis para CPU, GPU, temperatura e informações do sistema.
- Monitor flutuante de frequência de CPU e GPU, atualizando a cada segundo sobre outros aplicativos. Funciona em serviço em primeiro plano depois que a interface do manager é fechada.
- Perfis de desempenho com leitura e aplicação das configurações compatíveis com o kernel instalado.
- Opções de reaplicação após reinicialização e monitoramento em segundo plano.
- Perfil por app: em **Perfis → Perfil por app**, escolha um app e um perfil. O perfil é aplicado quando o app abre e o anterior volta quando você sai. Perfis marcados com ⚠ não são aceitos, e o modo seguro da guarda térmica tem prioridade.
- Registro das operações executadas pelo app.

Para usar o monitor flutuante, abra **Perfis**, toque em **Iniciar monitor flutuante** e autorize **Exibir sobre outros apps**. O aparelho também precisa conceder acesso root ao app para ler as frequências. A janela pode ser arrastada pelo cabeçalho e fechada pelo botão × ou pela notificação persistente.

A disponibilidade de controles depende do kernel e do firmware do aparelho. Revise cada perfil e os valores exibidos antes de aplicá-los.

## Compilar

Requer JDK 17 e Android SDK 35. Para gerar o APK release:

```sh
./gradlew assembleRelease
```

O release local é assinado com a chave debug para instalação pessoal. O workflow do GitHub Actions também gera o APK e publica o arquivo como artefato da execução.

## Stack

- Kotlin e Jetpack Compose
- Android Gradle Plugin 8.7.2 / Kotlin 2.0.21
- libsu para execução de comandos root
