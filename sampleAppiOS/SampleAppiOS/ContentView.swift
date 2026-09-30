//
//  ContentView.swift
//  SampleAppiOS
//
//  Created by Adam Brown on 1/2/26.
//

import SwiftUI
import SampleApp

struct ContentView: View {
    var body: some View {
        // Compose moves its content for the keyboard itself; letting SwiftUI shrink the
        // view as well moves it twice, leaving an empty band above the keyboard.
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

#Preview {
    ContentView()
}
