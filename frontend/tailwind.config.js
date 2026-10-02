/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // One scale, one meaning. Status colours live in `statusColors` below so a
        // status never picks up an arbitrary palette colour at the call site.
        primary: {
          DEFAULT: '#2563EB',
          dark: '#1D4ED8',
          light: '#EFF6FF',
        },
        surface: {
          DEFAULT: '#FFFFFF',
          muted: '#F8FAFC',
        },
        content: {
          DEFAULT: '#0F172A',
          muted: '#64748B',
          subtle: '#94A3B8',
        },
        edge: {
          DEFAULT: '#E2E8F0',
          strong: '#CBD5E1',
        },
        success: '#16A34A',
        warning: '#F59E0B',
        danger: '#DC2626',
        info: '#0EA5E9',
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
      },
      fontSize: {
        'page-title': ['24px', { lineHeight: '28px' }],
        'section-title': ['18px', { lineHeight: '24px' }],
        'card-title': ['15px', { lineHeight: '20px' }],
        body: ['14px', { lineHeight: '20px' }],
        small: ['12px', { lineHeight: '16px' }],
      },
      spacing: {
        18: '72px',
      },
      borderRadius: {
        card: '12px',
        control: '8px',
      },
      boxShadow: {
        // Restrained on purpose: cards are separated by their border, not by a glow.
        card: '0 1px 2px 0 rgb(15 23 42 / 0.04)',
        raised: '0 4px 12px -2px rgb(15 23 42 / 0.10)',
        overlay: '0 12px 32px -8px rgb(15 23 42 / 0.24)',
      },
      transitionDuration: {
        150: '150ms',
      },
    },
  },
  plugins: [],
}
