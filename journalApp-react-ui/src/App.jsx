import { useState, useEffect } from 'react';

const API_BASE_URL = "http://localhost:8080";

function App() {
    const [view, setView] = useState('loading');
    const [token, setToken] = useState(null);
    const [textToValidate, setTextToValidate] = useState('');

    // Auth State
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [username, setUsername] = useState('');

    // Login State
    const [loginError, setLoginError] = useState('');
    const [isLoggingIn, setIsLoggingIn] = useState(false);

    // Signup State
    const [signupError, setSignupError] = useState('');
    const [isSigningUp, setIsSigningUp] = useState(false);
    const [signupSuccess, setSignupSuccess] = useState('');

    // Result State
    const [result, setResult] = useState(null);

    useEffect(() => {
        if (typeof chrome !== 'undefined' && chrome.storage) {
            chrome.storage.local.get(['jwtToken', 'selectedTextToValidate', 'selectedText'], (data) => {
                if (data.jwtToken) {
                    setToken(data.jwtToken);
                    setView('validation');
                } else {
                    setView('login');
                }
                // Check both key names — whichever background.js uses
                const selected = data.selectedTextToValidate || data.selectedText || '';
                if (selected) {
                    setTextToValidate(selected);
                    chrome.storage.local.remove(['selectedTextToValidate', 'selectedText']);
                }
            });
        } else {
            setView('login');
        }
    }, []);

    // Listen for text updates while popup is already open
    useEffect(() => {
        if (typeof chrome === 'undefined' || !chrome.storage) return;
        const listener = (changes) => {
            const newText = changes.selectedTextToValidate?.newValue || changes.selectedText?.newValue;
            if (newText) {
                setTextToValidate(newText);
                chrome.storage.local.remove(['selectedTextToValidate', 'selectedText']);
            }
        };
        chrome.storage.onChanged.addListener(listener);
        return () => chrome.storage.onChanged.removeListener(listener);
    }, []);

    const handleSignup = async (e) => {
        e.preventDefault();
        setSignupError('');
        setSignupSuccess('');
        setIsSigningUp(true);
        try {
            const response = await fetch(`${API_BASE_URL}/api/auth/signup`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, username, password })
            });
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || "Signup failed");
            }
            setSignupSuccess("Account created successfully. You can now log in.");
            setTimeout(() => setView('login'), 2000);
        } catch (error) {
            setSignupError(error.message || "Failed to create account. Please try again.");
        } finally {
            setIsSigningUp(false);
        }
    };

    const handleLogin = async (e) => {
        e.preventDefault();
        setLoginError('');
        setIsLoggingIn(true);
        try {
            const response = await fetch(`${API_BASE_URL}/api/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, password })
            });
            if (!response.ok) throw new Error("Invalid credentials");
            const data = await response.json();
            if (data && data.token) {
                setToken(data.token);
                if (typeof chrome !== 'undefined' && chrome.storage) {
                    chrome.storage.local.set({ jwtToken: data.token });
                }
                setView('validation');
            } else {
                throw new Error("Token not received");
            }
        } catch (error) {
            setLoginError("Invalid credentials. Please try again.");
        } finally {
            setIsLoggingIn(false);
        }
    };

    const handleLogout = () => {
        setToken(null);
        setPassword('');
        setUsername('');
        setSignupSuccess('');
        setSignupError('');
        if (typeof chrome !== 'undefined' && chrome.storage) {
            chrome.storage.local.remove('jwtToken');
        }
        setView('login');
    };

    const handleValidate = async () => {
        if (!textToValidate.trim()) {
            alert("No text selected. Highlight text on any page and right-click to validate.");
            return;
        }
        if (!token) {
            setView('login');
            return;
        }
        setView('loading-result');
        try {
            // KEY FIX: send as JSON with rawText field — not plain text
            // Plain text body means Spring Boot @RequestBody gets null rawText → always UNVERIFIABLE
            const response = await fetch(`${API_BASE_URL}/api/fact-check`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify({ rawText: textToValidate })
            });
            if (response.status === 401 || response.status === 403) {
                handleLogout();
                return;
            }
            if (!response.ok) throw new Error(`Server error: ${response.status}`);
            const data = await response.json();
            setResult(data);
            setView('result');
        } catch (error) {
            alert(`Error: ${error.message}. Ensure the backend is running.`);
            setView('validation');
        }
    };

    const renderLoginView = () => (
        <div className="space-y-6 animate-fade-in transition-all duration-300">
            <div className="text-center">
                <h2 className="text-2xl font-bold tracking-tight text-white mb-2">Login</h2>
                <p className="text-sm text-slate-400">Sign in to verify news using AI</p>
            </div>
            <form onSubmit={handleLogin} className="space-y-4">
                <div>
                    <label className="block text-sm font-medium text-slate-300">Email Address</label>
                    <div className="mt-1">
                        <input
                            type="email"
                            required
                            className="input-field"
                            value={email}
                            onChange={e => setEmail(e.target.value)}
                        />
                    </div>
                </div>
                <div>
                    <label className="block text-sm font-medium text-slate-300">Password</label>
                    <div className="mt-1">
                        <input
                            type="password"
                            required
                            className="input-field"
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                        />
                    </div>
                </div>
                {loginError && <div className="text-red-400 text-sm">{loginError}</div>}
                <button type="submit" className="btn-primary mt-4" disabled={isLoggingIn}>
                    {isLoggingIn ? "Signing In..." : "Sign In"}
                </button>
            </form>
            <div className="text-center mt-4">
                <p className="text-sm text-slate-400">
                    Don't have an account?{' '}
                    <button type="button"
                        onClick={() => { setView('signup'); setLoginError(''); }}
                        className="text-indigo-400 hover:text-indigo-300 font-medium transition-colors">
                        Sign up
                    </button>
                </p>
            </div>
        </div>
    );

    const renderSignupView = () => (
        <div className="space-y-6 animate-fade-in transition-all duration-300">
            <div className="text-center">
                <h2 className="text-2xl font-bold tracking-tight text-white mb-2">Create Account</h2>
                <p className="text-sm text-slate-400">Join to verify news using AI</p>
            </div>
            <form onSubmit={handleSignup} className="space-y-4">
                <div>
                    <label className="block text-sm font-medium text-slate-300">Username</label>
                    <div className="mt-1">
                        <input
                            type="text"
                            required
                            className="input-field"
                            value={username}
                            onChange={e => setUsername(e.target.value)}
                        />
                    </div>
                </div>
                <div>
                    <label className="block text-sm font-medium text-slate-300">Email Address</label>
                    <div className="mt-1">
                        <input
                            type="email"
                            required
                            className="input-field"
                            value={email}
                            onChange={e => setEmail(e.target.value)}
                        />
                    </div>
                </div>
                <div>
                    <label className="block text-sm font-medium text-slate-300">Password</label>
                    <div className="mt-1">
                        <input
                            type="password"
                            required
                            className="input-field"
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                        />
                    </div>
                </div>
                {signupError   && <div className="text-red-400 text-sm">{signupError}</div>}
                {signupSuccess && <div className="text-emerald-400 text-sm">{signupSuccess}</div>}
                <button type="submit" className="btn-primary mt-4" disabled={isSigningUp}>
                    {isSigningUp ? "Creating Account..." : "Sign Up"}
                </button>
            </form>
            <div className="text-center mt-4">
                <p className="text-sm text-slate-400">
                    Already have an account?{' '}
                    <button type="button"
                        onClick={() => { setView('login'); setSignupError(''); setSignupSuccess(''); }}
                        className="text-indigo-400 hover:text-indigo-300 font-medium transition-colors">
                        Log in
                    </button>
                </p>
            </div>
        </div>
    );

    const renderValidationView = () => (
        <div className="space-y-4 animate-fade-in transition-all duration-300">
            <div className="text-center pb-4 border-b border-slate-700">
                <h2 className="text-xl font-bold text-white">Fact Check</h2>
                <p className="text-xs text-slate-400 mt-1">Ready to verify</p>
            </div>
            <div className="bg-slate-800 rounded-lg p-4 border border-slate-700 max-h-48 overflow-y-auto custom-scrollbar">
                <p className="text-sm text-slate-300 italic">
                    {textToValidate
                        ? `"${textToValidate.substring(0, 150)}${textToValidate.length > 150 ? '...' : ''}"`
                        : "No text selected. Highlight text on any page and right-click to validate."}
                </p>
            </div>
            <button
                onClick={handleValidate}
                disabled={!textToValidate.trim()}
                className="btn-primary mt-4 flex items-center justify-center gap-2 group disabled:opacity-40 disabled:cursor-not-allowed">
                <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5 group-hover:scale-110 transition-transform" viewBox="0 0 20 20" fill="currentColor">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                </svg>
                Validate Claim
            </button>
            <div className="text-center pt-2">
                <button onClick={handleLogout} className="text-xs text-slate-400 hover:text-white transition-colors underline">
                    Logout
                </button>
            </div>
        </div>
    );

    const renderLoadingView = () => (
        <div className="flex flex-col items-center justify-center py-8 animate-fade-in h-64">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-500 mb-4"></div>
            <p className="text-slate-300 text-sm font-medium animate-pulse">Analyzing claim...</p>
            <p className="text-slate-500 text-xs mt-2">Fetching news & running AI verification</p>
        </div>
    );

    const renderResultView = () => {
        if (!result) return null;

        const verdict = (result?.aiVerdict || "UNVERIFIABLE").toUpperCase();
        const explanation = result?.aiExplanation || "No explanation provided.";
        const urls = result?.sourceUrls || [];
        const score = result?.confidenceScore != null ? Math.round(result.confidenceScore * 100) : null;

        let themeClass = 'bg-amber-900/40 border-amber-500 text-amber-400';
        if (verdict === "TRUE")  themeClass = 'bg-emerald-900/40 border-emerald-500 text-emerald-400';
        if (verdict === "FALSE") themeClass = 'bg-red-900/40 border-red-500 text-red-400';

        return (
            <div className="space-y-4 animate-fade-in transition-all duration-500">
                <button
                    onClick={() => { setView('validation'); setResult(null); }}
                    className="text-slate-400 hover:text-white text-sm mb-2 flex items-center gap-1 transition-colors group">
                    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 group-hover:-translate-x-1 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
                    </svg>
                    Back
                </button>

                {/* Verdict Banner */}
                <div className={`rounded-xl p-6 text-center shadow-[0_0_15px_rgba(0,0,0,0.2)] border ${themeClass} transform transition-all hover:scale-[1.02]`}>
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-200 mb-1 opacity-80">AI Verdict</h3>
                    <div className="text-3xl font-black mb-2 uppercase tracking-tight">{verdict}</div>
                </div>

                {/* Confidence Score Bar */}
                {score !== null && (
                    <div className="bg-slate-800 border border-slate-700 rounded-lg px-4 py-3">
                        <div className="flex items-center justify-between mb-1.5">
                            <span className="text-xs text-slate-400 font-medium">Source Relevance</span>
                            <span className="text-xs font-bold text-white">{score}%</span>
                        </div>
                        <div className="h-1.5 bg-slate-700 rounded-full overflow-hidden">
                            <div
                                className="h-full rounded-full bg-gradient-to-r from-indigo-500 to-cyan-400 transition-all duration-700"
                                style={{ width: `${score}%` }}
                            />
                        </div>
                    </div>
                )}

                {/* Explanation */}
                <div className="bg-slate-800 border border-slate-700 rounded-lg p-4 shadow-inner">
                    <h4 className="text-xs font-semibold text-slate-400 mb-2 uppercase tracking-wide">Explanation</h4>
                    <p className="text-sm text-slate-300 max-h-40 overflow-y-auto leading-relaxed custom-scrollbar">
                        {explanation}
                    </p>
                </div>

                {/* Source URLs */}
                {urls.length > 0 && (
                    <div className="bg-slate-800 border border-slate-700 rounded-lg p-4">
                        <h4 className="text-xs font-semibold text-slate-400 mb-2 uppercase tracking-wide">Sources</h4>
                        <div className="space-y-2">
                            {urls.map((url, i) => {
                                let domain = url;
                                try { domain = new URL(url).hostname.replace('www.', ''); } catch {}
                                return (
                                    <a
                                        key={i}
                                        href={url}
                                        target="_blank"
                                        rel="noreferrer"
                                        className="flex items-center gap-2 text-xs text-indigo-400 hover:text-indigo-300 transition-colors group"
                                    >
                                        <span className="w-1.5 h-1.5 rounded-full bg-indigo-500/60 flex-shrink-0"></span>
                                        <span className="truncate group-hover:underline">{domain}</span>
                                        <span className="flex-shrink-0 opacity-50">↗</span>
                                    </a>
                                );
                            })}
                        </div>
                    </div>
                )}
            </div>
        );
    };

    return (
        <div className="flex flex-col items-center justify-center p-6 h-screen w-full">
            <div className="glass-panel w-full max-w-sm p-8 space-y-6 relative z-10 transition-all duration-300 min-h-[400px] flex flex-col justify-center">
                {view === 'loading'        && <div className="text-center text-slate-400">Loading...</div>}
                {view === 'login'          && renderLoginView()}
                {view === 'signup'         && renderSignupView()}
                {view === 'validation'     && renderValidationView()}
                {view === 'loading-result' && renderLoadingView()}
                {view === 'result'         && renderResultView()}
            </div>

            <style dangerouslySetInnerHTML={{ __html: `
                .custom-scrollbar::-webkit-scrollbar { width: 6px; }
                .custom-scrollbar::-webkit-scrollbar-track { background: transparent; }
                .custom-scrollbar::-webkit-scrollbar-thumb { background: #475569; border-radius: 10px; }
                .custom-scrollbar::-webkit-scrollbar-thumb:hover { background: #64748b; }
                @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
                .animate-fade-in { animation: fadeIn 0.4s ease-out forwards; }
            `}} />
        </div>
    );
}

export default App;