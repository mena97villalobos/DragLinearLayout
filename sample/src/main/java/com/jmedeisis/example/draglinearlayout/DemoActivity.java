package com.jmedeisis.example.draglinearlayout;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.jmedeisis.draglinearlayout.DragLinearLayout;

public class DemoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        DragLinearLayout dragLinearLayout = (DragLinearLayout) findViewById(R.id.container);
        // set all children draggable except the first (the header)
        for(int i = 1; i < dragLinearLayout.getChildCount(); i++){
            View child = dragLinearLayout.getChildAt(i);
            dragLinearLayout.setViewDraggable(child, child); // the child is its own drag handle
            child.setOnDragListener(new View.OnDragListener() {
                @Override
                public boolean onDrag(View view, DragEvent dragEvent) {
                    if (dragEvent.getAction() == DragEvent.ACTION_DRAG_STARTED)
                        view.setBackgroundColor(Color.RED);
                    else if (dragEvent.getAction() == DragEvent.ACTION_DRAG_ENDED)
                        view.setBackgroundColor(Color.WHITE);
                    return false;
                }
            });
        }

        findViewById(R.id.noteDemoButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(DemoActivity.this, NoteActivity.class));
            }
        });
    }
}
