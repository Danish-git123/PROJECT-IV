/** @type {import('tailwindcss').Config} */
export default {
    content: [
        "./index.html",
        "./src/**/*.{js,ts,jsx,tsx}",
    ],
    theme: {
        extend: {
            fontFamily: {
                sans: ['Inter', 'sans-serif'],
            },
            colors: {
                verdict: {
                    true: '#10b981', // emerald-500
                    false: '#ef4444', // red-500
                    unverified: '#f59e0b', // amber-500
                }
            }
        },
    },
    plugins: [],
}
