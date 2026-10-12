import { defineConfig } from 'vitepress'

// 部署说明：
// - GitHub Pages 项目页（wngbob.github.io/oryxos-wngbob）：base 保持 '/oryxos-wngbob/'
// - 若绑定独立域名 oryxos.wngbob.com（CNAME 指向 Pages）：base 必须改为 '/'，
//   并在 website/public/ 下放置 CNAME 文件（内容：oryxos.wngbob.com）
const sharedThemeConfig = {
  logo: '/images/logo-icon.svg',
  socialLinks: [
    { icon: 'github', link: 'https://github.com/wngbob/oryxos-wngbob' }
  ],
  search: {
    provider: 'local'
  }
}

const guideSidebarZh = [
  {
    text: '开始',
    items: [
      { text: '什么是 OryxOS', link: '/guide/what-is-oryxos' },
      { text: '快速开始', link: '/guide/quick-start' }
    ]
  },
  {
    text: '深入',
    items: [
      { text: '架构', link: '/guide/architecture' },
      { text: '设计原则', link: '/guide/design-principles' },
      { text: '路线图', link: '/guide/roadmap' }
    ]
  }
]

const guideSidebarEn = [
  {
    text: 'Getting Started',
    items: [
      { text: 'What is OryxOS', link: '/en/guide/what-is-oryxos' },
      { text: 'Quick Start', link: '/en/guide/quick-start' }
    ]
  },
  {
    text: 'Deep Dive',
    items: [
      { text: 'Architecture', link: '/en/guide/architecture' },
      { text: 'Design Principles', link: '/en/guide/design-principles' },
      { text: 'Roadmap', link: '/en/guide/roadmap' }
    ]
  }
]

export default defineConfig({
  base: '/oryxos-wngbob/',
  title: 'OryxOS',
  description: '企业 Agent 操作系统（Agent Harness OS）—— 让每一家公司，都能用自然语言跑起来自己的 Agent',
  lang: 'zh-CN',
  cleanUrls: true,
  lastUpdated: false,

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/oryxos/images/logo-icon.svg' }]
  ],

  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      themeConfig: {
        ...sharedThemeConfig,
        nav: [
          { text: '指南', link: '/guide/what-is-oryxos' },
          { text: '架构', link: '/guide/architecture' },
          { text: '路线图', link: '/guide/roadmap' }
        ],
        sidebar: {
          '/guide/': guideSidebarZh
        },
        outline: { label: '本页目录' },
        docFooter: { prev: '上一页', next: '下一页' },
        returnToTopLabel: '回到顶部',
        darkModeSwitchLabel: '外观'
      }
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      themeConfig: {
        ...sharedThemeConfig,
        nav: [
          { text: 'Guide', link: '/en/guide/what-is-oryxos' },
          { text: 'Architecture', link: '/en/guide/architecture' },
          { text: 'Roadmap', link: '/en/guide/roadmap' }
        ],
        sidebar: {
          '/en/guide/': guideSidebarEn
        },
        outline: { label: 'On this page' }
      }
    }
  }
})
