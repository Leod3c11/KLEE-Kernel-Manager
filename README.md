# KLEE Kernel Manager

Gerenciador Android especializado e validado no hardware real do **POCO X8 Pro** (`2511FPC34G`, codinome `klee`, plataforma **MediaTek MT6899**, Mali-G720, HyperOS 3 / Android 16, Linux `6.6.89`, KernelSU).

## Funcionalidades (v0.3.0 — Mapeado no Kernel Real)

- **Dashboard ao Vivo**: telemetria em tempo real de CPU pico, GPU Mali (MHz + `%` via GED), DRAM LPDDR5X (`mtk-dvfsrc-devfreq`), SoC Max °C, bateria, status de Bypass Charging, barra de RAM, status Root e troca rápida de perfis.
- **CPU & UCLAMP**:
  - 3 clusters reais (`policy0` Little 300–2200 MHz, `policy4` Medium 400–3200 MHz, `policy7` Prime 1000–3400 MHz), suporte nativo ao governor **`sugov_ext`**, grade de núcleos online em tempo real, seletores travados na tabela real de frequências e rollback transacional.
  - Controle de **UCLAMP (`top-app`)**: `cpu.uclamp.min`, `cpu.uclamp.max` e `cpu.uclamp.latency_sensitive`.
- **GPU Mali, MTK GED & DRAM LPDDR5X**:
  - Controle devfreq `13000000.mali` sobre as **49 OPPs reais (260–1508 MHz)** + diagnóstico seguro `gpufreqv2`.
  - Controles reais do **MTK GED**: uso da GPU em `%` (`gpu_utilization`), `ged_smart_boost`, `boost_gpu_enable`, `is_GED_KPI_enabled`, `dcs_mode` (Dynamic Core Scaling) e `dvfs_margin_value`.
  - Controle de piso/teto de **DRAM LPDDR5X** (`mtk-dvfsrc-devfreq`, 10 OPPs de 757 a 9600 MHz).
- **MTK FPSGO & Xiaomi Metis** *(Novo)*:
  - Controles diretos de **MediaTek FPSGO** (`fpsgo_enable`, `boost_ta`, `boost_VIP`, `rescue_enable`, `ultra_rescue`, `powerRL_enable`, `fpsgo_status`).
  - Controles de **Xiaomi Metis** (`mi_fboost_enable`, `speed_touch_enable`, `vip_link_enable`, `metis_io_enabled`, `in_perf_mod`, `thermal_break_enable`).
  - Presets de 1 toque: *Gaming Anti-Stutter*, *Stock HyperOS* e *Frio / Estável*.
- **Monitor Térmico**: KPIs dedicados para `soc_max`, `cpu-big`, `battery` e throttling ativo, filtragem automática de sensores inativos (`-274°C`/`0°C`), busca instantânea e barras de estado para `cooling_device`.
- **Bateria & Bypass Charging**:
  - Telemetria elétrica em **V**, **mA**, **W** e **°C**, saúde real (ex.: 6318/6500 mAh), contagem de ciclos e fontes de alimentação.
  - **Bypass Charging real** (`/sys/class/power_supply/battery/input_suspend`) com diálogo de confirmação e verificação read-back.
- **Monitor ao Vivo**: 3 gráficos em tempo real (**CPU %**, **GPU Mali %** via GED e **SoC Max °C**), clocks por cluster/GPU/DRAM/UFS, RAM/Swap, Load Average, Uptime, Pausa/Retomar e intervalos (`1s` / `2s` / `5s`).
- **Memória, ZRAM, MGLRU, UFS 4.0 & Rede**:
  - Taxa de compressão real do ZRAM (`mm_stat`) e economia de RAM em MB + limpeza segura de caches.
  - Toggle de **Multi-Gen LRU (MGLRU)** (`/sys/kernel/mm/lru_gen/enabled`).
  - Sliders VM (`swappiness`, `vfs_cache_pressure`, `dirty_ratio`, `watermark_scale_factor`).
  - Controles **UFS 4.0** (`112b0000.ufshci`): clock UFS, **WriteBooster (`wb_on`)**, `clkscale_enable` e `clkgate_enable`.
  - I/O Scheduler (`none`, `mq-deadline`, `kyber`, `bfq`), `read_ahead_kb` e `iostats` por dispositivo de bloco.
  - **TCP Congestion** (`bbr`, `cubic`, `reno`) + **TCP Fast Open** (`0`, `1`, `3`).
- **Perfis & Dispositivo**: 4 perfis inteligentes + perfis personalizados salvos localmente + identidade completa (HyperOS/Kernel/SELinux) + exportação de relatório e histórico de ações root.

## Segurança

Nenhuma leitura cega de diretórios proprietários é realizada. Todas as escritas usam listas brancas estritas de caminhos validados no kernel real, validação de limites e leitura de confirmação (*read-back*).
