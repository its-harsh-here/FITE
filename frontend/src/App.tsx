import { useState, useEffect } from 'react';
import { LandingPage } from './pages/LandingPage';
import { SendPage } from './pages/SendPage';
import { ReceivePage } from './pages/ReceivePage';

function App() {
  const [route, setRoute] = useState<string>(window.location.pathname);

  const navigate = (path: string) => {
    setRoute(path);
    window.history.pushState({}, '', path);
  };

  useEffect(() => {
    const handlePopState = () => {
      setRoute(window.location.pathname);
    };
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  let page;
  if (route.startsWith('/send')) {
    page = <SendPage onHome={() => navigate('/')} />;
  } else if (route.startsWith('/receive') || route.startsWith('/download/') || route.startsWith('/transfer/')) {
    page = <ReceivePage onHome={() => navigate('/')} />;
  } else {
    page = <LandingPage navigate={navigate} />;
  }

  return (
    <div className="app-container">
      {page}
    </div>
  );
}

export default App;
