import { CheckCircle2, FolderOpen, GitBranch, Package, Terminal } from 'lucide-react';

const CHECKLIST = [
  'Отправь репозиторий на GitHub (ветка main или master) и открой вкладку Actions → Build Android APK.',
  'Скачай артефакт sandboxforge-debug-apk (app-debug.apk) и установи на Android-устройство (API 26+).',
  'Вкладка «Контейнер»: импортируй приложение с телефона (проверь, что подтянулись base.apk + сплиты) и попробуй установить внешний .apk.',
  'Вкладка «Файлы»: перейди в папку контейнера, создай папку, посмотри текстовый файл и проверь экспорт/импорт файла.',
  'Вкладка «Логи»: проверь запись всех действий, фильтр уровней, кнопку «Копировать» в буфер и сохранение лога в файл.',
];

const MODULES = [
  {
    icon: Package,
    title: 'Контейнер #1 (default)',
    detail: 'Изолированный корень apks/, data/, sdcard/, meta/apps.json. Копирование base.apk + split_*.apk с флагом read-only (Android 14+).',
  },
  {
    icon: FolderOpen,
    title: 'Файловый менеджер контейнера',
    detail: 'Строгий джейл внутри корня контейнера (проверка canonicalPath), создание папок, предпросмотр до 128 КБ, экспорт/импорт через SAF.',
  },
  {
    icon: Terminal,
    title: 'Окно логов + краш-логгер',
    detail: 'Живой поток событий, копирование в буфер обмена, экспорт в файл, автозапись session_*.log и синхронный дамп last_crash.log при падении.',
  },
  {
    icon: GitBranch,
    title: 'GitHub Actions CI',
    detail: 'Автоматический запуск JUnit-тестов и сборка app-debug.apk на JDK 17 + Android SDK 35 + Gradle 8.10.2.',
  },
];

export default function App() {
  return (
    <div className="min-h-screen bg-[#0F1115] text-[#F3F4F6] px-6 py-10">
      <div className="mx-auto max-w-4xl space-y-8">
        <header className="border-b border-[#282E3D] pb-6">
          <p className="font-mono text-xs uppercase tracking-widest text-[#22D3EE]">
            Android Kotlin • Jetpack Compose • Step 1 (MVP v0.1)
          </p>
          <h1 className="mt-2 text-3xl font-semibold tracking-tight">
            SandboxForge — каркас песочницы готов к сборке
          </h1>
          <p className="mt-2 text-sm text-[#9CA3AF]">
            Исходный код Android-приложения расположен в каталоге{' '}
            <code className="rounded bg-[#171A21] px-1.5 py-0.5 font-mono text-[#22D3EE]">
              android/
            </code>
            , а пайплайн автосборки APK — в{' '}
            <code className="rounded bg-[#171A21] px-1.5 py-0.5 font-mono text-[#22D3EE]">
              .github/workflows/android-build.yml
            </code>
            .
          </p>
        </header>

        <section className="grid gap-4 sm:grid-cols-2">
          {MODULES.map((item) => {
            const Icon = item.icon;
            return (
              <div
                key={item.title}
                className="rounded-xl border border-[#282E3D] bg-[#171A21] p-5"
              >
                <div className="flex items-center gap-2.5">
                  <Icon className="h-5 w-5 text-[#22D3EE]" />
                  <h2 className="text-base font-medium">{item.title}</h2>
                </div>
                <p className="mt-2 text-sm leading-relaxed text-[#9CA3AF]">
                  {item.detail}
                </p>
              </div>
            );
          })}
        </section>

        <section className="rounded-xl border border-[#282E3D] bg-[#171A21] p-6">
          <h2 className="text-base font-semibold">Как проверить Шаг 1 на телефоне</h2>
          <ol className="mt-4 space-y-3 text-sm text-[#D1D5DB]">
            {CHECKLIST.map((step, idx) => (
              <li key={idx} className="flex items-start gap-2.5">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-[#22C55E]" />
                <span>{step}</span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </div>
  );
}
